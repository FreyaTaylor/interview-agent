package com.interview.agent.admin.service.impl;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.interview.agent.admin.dto.CreateTreeFromGenerateReq;
import com.interview.agent.admin.dto.CreateTreeFromTextReq;
import com.interview.agent.admin.dto.TreeGenResp;
import com.interview.agent.admin.dto.TreeNodeJson;
import com.interview.agent.admin.service.TreeGenService;
import com.interview.agent.common.BizException;
import com.interview.agent.common.JsonUtil;
import com.interview.agent.infra.llm.EmbeddingService;
import com.interview.agent.knowledge.entity.KnowledgeNode;
import com.interview.agent.knowledge.mapper.KnowledgeNodeMapper;
import com.interview.agent.prompts.PromptLoader;
import com.interview.agent.user.mapper.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 知识树生成服务实现（S5：from-text + from-generate）。
 *
 * <p>整体流程（两个入口共用）：
 * <ol>
 *   <li>构造 prompt（不同入口用不同模板）</li>
 *   <li>调 DeepSeek（{@link ChatClient}），最多重试 3 次 → 拿到 JSON 字符串</li>
 *   <li>{@link JsonUtil#extractJson} 容错抽取 + 反序列化为 {@link TreeNodeJson}</li>
 *   <li>{@link #checkDuplicateByName} 做两层去重（精确名 + LLM 语义）</li>
 *   <li>递归 {@link #saveRecursive} 落库（每个节点同步生成 embedding，失败降级为 null）</li>
 *   <li>事务提交，返回 root_id + name + leaf_count</li>
 * </ol>
 *
 * <p>设计取舍：
 * <ul>
 *   <li>embedding 同步生成（而非 Python 那样后批量回填）—— 简化事务边界；
 *       单个节点 embed 失败降级为 null 不阻断整树，与 S1 KnowledgeAdminServiceImpl 一致。</li>
 *   <li>level 在递归里现算（base=1，每深一层 +1），与 S1 create() 单节点 level 推导一致。</li>
 *   <li>nodeType：有 children → category，否则 leaf（与 S1 "默认 leaf 后升级" 路径不同 ——
 *       树生成时整棵都知道形状，可一次定型，无需事后升级）。</li>
 * </ul>
 *
 * <p>用户：一期写死 user_id=1（CONVENTIONS §1，多用户在 S9 引入）。
 */
@Service
public class TreeGenServiceImpl implements TreeGenService {

    private static final Logger log = LoggerFactory.getLogger(TreeGenServiceImpl.class);
    private static final int LLM_RETRY = 3;
    private static final short DEFAULT_WEIGHT = 3;
    private static final long DEFAULT_USER_ID = 1L;
    /** LLM 单次最大输出 token；Python 端 8192，避免大树被截断。 */
    private static final int LLM_MAX_TOKENS = 8192;
    /** 文本解析温度（与 Python create_tree_from_text 对齐）：低温防止 LLM 改写原文。 */
    private static final double TEMP_PARSE_TEXT = 0.1;
    /** 树生成温度（与 Python create_tree_from_generate 对齐）：中温保留发散性。 */
    private static final double TEMP_GENERATE = 0.3;
    /** 语义去重温度（与 Python _check_duplicate_by_name 对齐）：零温要确定答案。 */
    private static final double TEMP_DUP_CHECK = 0.0;

    private final KnowledgeNodeMapper repo;
    private final UserMapper userMapper;
    private final EmbeddingService embeddingService;
    private final ChatClient chatClient;
    private final PromptLoader promptLoader;

    public TreeGenServiceImpl(KnowledgeNodeMapper repo,
                              UserMapper userMapper,
                              EmbeddingService embeddingService,
                              ChatClient chatClient,
                              PromptLoader promptLoader) {
        this.repo = repo;
        this.userMapper = userMapper;
        this.embeddingService = embeddingService;
        this.chatClient = chatClient;
        this.promptLoader = promptLoader;
    }

    // ============================================================
    // 入口 1：from-text
    // ============================================================

    /**
     * 解析用户粘贴的文本（Markdown / 缩进列表）为知识树并落库。
     *
     * <p>LLM 只做"结构识别"——名称严格保持原文，不改写不归类（prompt 里有硬约束）。
     *
     * @throws BizException 40001 text 空；40901 同名树已存在；50000 LLM 解析失败
     */
    @Override
    @Transactional
    public TreeGenResp createFromText(CreateTreeFromTextReq req) {
        // Step 1: 入参校验
        String text = req.text() == null ? "" : req.text().strip();
        if (text.isEmpty()) {
            throw new BizException(40001, "文本内容不能为空");
        }

        // Step 2: 渲染 prompt + 调 LLM（带 3 次重试，低温模式严守原文）
        String prompt = promptLoader.render("tree/parse-text.txt", Map.of("text", text));
        TreeNodeJson tree = callLlmJson(prompt, "from-text", TEMP_PARSE_TEXT, TreeNodeJson.class);

        // Step 3: 创建前去重（精确 + LLM 语义两层）
        String treeName = tree.name() == null ? "" : tree.name().strip();
        if (!treeName.isEmpty()) {
            checkDuplicateByName(treeName);
        }

        // Step 4: 递归落库 + 同步生成 embedding
        SaveResult sr = saveRecursive(tree, null, (short) 1, List.of());
        log.info("[TreeGen] from-text done root={} name='{}' leaves={}", sr.rootId, treeName, sr.leafCount);
        return new TreeGenResp(sr.rootId, treeName, sr.leafCount);
    }

    // ============================================================
    // 入口 2：from-generate
    // ============================================================

    /**
     * 给定根名称 + 可选需求描述，调 LLM 一次生成完整知识树并落库。
     *
     * <p>LLM 拿到 3 个变量：tree_name、requirements（空时回退用 tree_name）、profile_text（用户画像）。
     * LLM 返回 {@code {"children":[...]}} 不含根名，我们补上 {@code tree_name} 当根节点。
     *
     * @throws BizException 40001 treeName 空；40901 同名树已存在；50000 LLM 失败
     */
    @Override
    @Transactional
    public TreeGenResp createFromGenerate(CreateTreeFromGenerateReq req) {
        // Step 1: 入参校验 + requirements 默认回退
        String treeName = req.treeName() == null ? "" : req.treeName().strip();
        if (treeName.isEmpty()) {
            throw new BizException(40001, "树名称不能为空");
        }
        String requirements = (req.requirements() == null || req.requirements().isBlank())
                ? treeName : req.requirements().strip();

        // Step 2: 先去重（避免白调一次 LLM 再报重复）
        checkDuplicateByName(treeName);

        // Step 3: 读用户画像（一期 user_id=1）；缺失时塞占位文案
        String profileText = userMapper.findProfileText(DEFAULT_USER_ID)
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .orElse("（未设置用户画像）");

        // Step 4: 渲染 prompt + 调 LLM
        String prompt = promptLoader.render("tree/generate.txt", Map.of(
                "tree_name", treeName,
                "requirements", requirements,
                "profile_text", profileText
        ));
        TreeNodeJson llmTree = callLlmJson(prompt, "from-generate", TEMP_GENERATE, TreeNodeJson.class);

        // Step 5: LLM 不返回根名，用 treeName 包装一层作为根
        TreeNodeJson root = new TreeNodeJson(treeName, llmTree.children(), null);

        // Step 6: 递归落库 + 同步 embedding
        SaveResult sr = saveRecursive(root, null, (short) 1, List.of());
        log.info("[TreeGen] from-generate done root={} name='{}' leaves={}", sr.rootId, treeName, sr.leafCount);
        return new TreeGenResp(sr.rootId, treeName, sr.leafCount);
    }

    // ============================================================
    // 内部：递归落库
    // ============================================================

    /** 递归结果载体（局部用，不暴露）。 */
    private record SaveResult(long rootId, int leafCount) {}

    /**
     * 递归把 {@link TreeNodeJson} 写入 knowledge_node 表。
     *
     * <p>规则：
     * <ul>
     *   <li>nodeType：有 children → category，否则 leaf</li>
     *   <li>level：按 base 递增，根节点 level=1</li>
     *   <li>interviewWeight：节点自带 → 用；空 → {@value #DEFAULT_WEIGHT}</li>
     *   <li>embedding 文本：{@code "祖先 / ... / 当前节点名"}（与 Python _build_text 对齐，
     *       避免同名节点在不同上下文下被向量混淆，如 Redis/Set vs JS/Set）</li>
     *   <li>embed 失败：降级为 null（节点照常入库，等后续 backfill）</li>
     * </ul>
     *
     * @param node      当前要写入的节点
     * @param parentId  父节点 id（根节点传 null）
     * @param level     当前 level（根=1）
     * @param ancestors 祖先名链（不含 self），用于拼 embedding 文本
     * @return SaveResult{当前节点 id, 该子树叶子计数}
     */
    private SaveResult saveRecursive(TreeNodeJson node,
                                     Long parentId,
                                     short level,
                                     List<String> ancestors) {
        // Step 1: 名称清洗
        String name = node.name() == null ? "" : node.name().strip();
        if (name.isEmpty()) {
            throw new BizException(50000, "LLM 返回的节点 name 为空，无法落库");
        }

        // Step 1.5: 塌缩「单同名子节点」包装（与 Python save_tree_to_db 对齐）：
        // LLM 偶尔会返回 {"name":"Redis","children":[{"name":"Redis","children":[...]}]}
        // 这种自我包裹的结构，要把内层 children 上提，避免落两层同名节点。
        List<TreeNodeJson> raw = node.children();
        if (raw != null && raw.size() == 1) {
            TreeNodeJson only = raw.get(0);
            String onlyName = only.name() == null ? "" : only.name().strip();
            if (onlyName.equalsIgnoreCase(name) && only.children() != null && !only.children().isEmpty()) {
                node = new TreeNodeJson(name, only.children(), node.interviewWeight());
            }
        }

        // Step 2: 判定 nodeType / 权重
        boolean isLeaf = node.isLeaf();
        String nodeType = isLeaf ? "leaf" : "category";
        short weight = node.interviewWeight() != null ? node.interviewWeight() : DEFAULT_WEIGHT;

        // Step 3: 构造 embedding 文本（"父路径 / 当前名"），生成向量；失败降级 null
        String embedText = ancestors.isEmpty() ? name : String.join(" / ", ancestors) + " / " + name;
        String embeddingLiteral = safeEmbed(embedText);

        // Step 4: INSERT（带 / 不带 embedding 两个分支）
        long newId = (embeddingLiteral == null)
                ? repo.insertWithoutEmbedding(parentId, name, level, nodeType, weight, 0, false)
                : repo.insertWithEmbedding(parentId, name, level, nodeType, weight, 0, false, embeddingLiteral);

        // Step 5: 叶子终止递归，计数 +1
        if (isLeaf) {
            return new SaveResult(newId, 1);
        }

        // Step 6: 非叶递归子节点；累加叶子数；sort_order 按下标设
        int leafCount = 0;
        List<String> nextAncestors = new ArrayList<>(ancestors.size() + 1);
        nextAncestors.addAll(ancestors);
        nextAncestors.add(name);
        List<TreeNodeJson> children = node.children();
        for (int i = 0; i < children.size(); i++) {
            TreeNodeJson child = children.get(i);
            SaveResult childRes = saveRecursive(child, newId, (short) (level + 1), nextAncestors);
            // sort_order 后置 UPDATE（insertWithEmbedding/WithoutEmbedding 当前都写死 0）
            repo.updateSortOrder(childRes.rootId, i);
            leafCount += childRes.leafCount;
        }
        return new SaveResult(newId, leafCount);
    }

    /** 单点 embedding 调用：失败降级为 null（与 KnowledgeAdminServiceImpl 行为一致）。 */
    private String safeEmbed(String text) {
        try {
            return embeddingService.embedToLiteral(text);
        } catch (Exception e) {
            log.warn("[TreeGen] embedding 失败，节点落库不带向量: text='{}' err={}", text, e.getMessage());
            return null;
        }
    }

    // ============================================================
    // 内部：LLM 调用 + JSON 解析（3 次重试）
    // ============================================================

    /**
     * 调 LLM 拿结构化 JSON，最多 {@value #LLM_RETRY} 次重试；按场景指定 temperature 与 max-tokens。
     *
     * <p>每次结果都过 {@link JsonUtil#extractJson} —— 即使 LLM 在 JSON 外塞了寒暄语，
     * 也能从代码围栏 / 括号配对里抠出来。
     *
     * @param prompt      渲染好的 prompt
     * @param scene       日志场景标签（如 "from-text"），便于排查
     * @param temperature 本次调用温度（覆盖 yml 的全局默认）
     * @param type        反序列化目标类型
     * @param <T>         返回结构
     * @return 解析后的 POJO
     * @throws BizException 50000 全部重试都失败
     */
    private <T> T callLlmJson(String prompt, String scene, double temperature, Class<T> type) {
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .temperature(temperature)
                .maxTokens(LLM_MAX_TOKENS)
                .build();
        Exception last = null;
        for (int attempt = 1; attempt <= LLM_RETRY; attempt++) {
            try {
                // Step 1: 调用 DeepSeek（per-call options 覆盖全局温度）
                String content = chatClient.prompt()
                        .options(options)
                        .user(prompt)
                        .call()
                        .content();
                // Step 2: 容错抽 JSON + 反序列化
                return JsonUtil.extractJson(content, type);
            } catch (Exception e) {
                last = e;
                log.warn("[TreeGen] {} LLM 第 {}/{} 次失败: {}", scene, attempt, LLM_RETRY, e.getMessage());
            }
        }
        throw new BizException(50000, "LLM 调用失败（" + scene + "）：" + (last != null ? last.getMessage() : "未知"));
    }

    // ============================================================
    // 内部：树名去重（精确 + LLM 语义两层）
    // ============================================================

    /**
     * 创建树前的同名检查（两层）：
     * <ol>
     *   <li>精确匹配（case-insensitive，trim 后比较）—— 命中即抛 40901</li>
     *   <li>LLM 语义匹配 —— 让 LLM 判断新名是否与某已有根节点同主题；命中抛 40901</li>
     * </ol>
     *
     * <p>第二层 LLM 失败时仅记 warning 不阻断（与 Python 一致 —— LLM 不可用不应该阻止合法创建）。
     *
     * @throws BizException 40901 重复
     */
    private void checkDuplicateByName(String name) {
        // Step 1: 拉所有根节点
        List<KnowledgeNode> roots = repo.findRoots();
        if (roots.isEmpty()) {
            return;
        }
        String lowered = name.toLowerCase(Locale.ROOT);

        // Step 2: 精确匹配
        for (KnowledgeNode r : roots) {
            String rn = r.name() == null ? "" : r.name().strip();
            if (rn.toLowerCase(Locale.ROOT).equals(lowered)) {
                throw new BizException(40901, "已存在同名知识树「" + r.name() + "」，请更换名称或合并");
            }
        }

        // Step 3: LLM 语义匹配（失败不阻断）
        List<String> existingNames = roots.stream()
                .map(r -> r.name() == null ? "" : r.name().strip())
                .filter(s -> !s.isEmpty())
                .toList();
        if (existingNames.isEmpty()) {
            return;
        }
        try {
            String namesText = existingNames.stream().map(n -> "- " + n).collect(Collectors.joining("\n"));
            String prompt = promptLoader.render("tree/duplicate-check.txt", Map.of(
                    "new_name", name,
                    "existing_names", namesText
            ));
            // 复用 callLlmJson：零温 + 单次重试逻辑也走完整 3 次（语义判定贵但偶发失败）
            DuplicateCheckResult res = callLlmJson(prompt, "dup-check", TEMP_DUP_CHECK, DuplicateCheckResult.class);
            if (res != null && Boolean.TRUE.equals(res.duplicate()) && res.matchedName() != null) {
                String matched = res.matchedName().strip();
                for (KnowledgeNode r : roots) {
                    String rn = r.name() == null ? "" : r.name().strip();
                    if (rn.toLowerCase(Locale.ROOT).equals(matched.toLowerCase(Locale.ROOT))) {
                        throw new BizException(40901, "与已有知识树「" + r.name() + "」语义重复，请更换名称或合并");
                    }
                }
            }
        } catch (BizException e) {
            throw e;  // 重复错误向上抛
        } catch (Exception e) {
            log.warn("[TreeGen] 语义去重检测失败（跳过）: {}", e.getMessage());
        }
    }

    /** LLM duplicate-check.txt 的输出结构。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record DuplicateCheckResult(
            Boolean duplicate,
            @JsonProperty("matched_name") String matchedName
    ) {}
}
