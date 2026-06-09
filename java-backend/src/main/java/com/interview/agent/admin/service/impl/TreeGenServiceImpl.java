package com.interview.agent.admin.service.impl;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.interview.agent.admin.dto.CreateTreeFromGenerateReq;
import com.interview.agent.admin.dto.CreateTreeFromTextReq;
import com.interview.agent.admin.dto.TreeGenResp;
import com.interview.agent.admin.dto.TreeNodeJson;
import com.interview.agent.admin.service.TreeGenService;
import com.interview.agent.auth.CurrentUser;
import com.interview.agent.common.BizException;
import com.interview.agent.common.JsonUtil;
import com.interview.agent.common.LlmInvoker;
import com.interview.agent.infra.llm.EmbeddingService;
import com.interview.agent.infra.llm.QwenVisionClient;
import com.interview.agent.knowledge.entity.KnowledgeNode;
import com.interview.agent.knowledge.mapper.KnowledgeNodeMapper;
import com.interview.agent.prompts.PromptKeys;
import com.interview.agent.prompts.PromptService;
import com.interview.agent.user.mapper.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
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
 *   <li>构造 vars（不同入口用不同 prompt key）</li>
 *   <li>调 {@link LlmInvoker}（最多重试 {@value #LLM_RETRY} 次，parser 用 {@link JsonUtil#extractJson} 容错抽 JSON）</li>
 *   <li>{@link #checkDuplicateByName} 做两层去重（精确名 + LLM 语义）</li>
 *   <li>递归 {@link #saveRecursive} 落库（每个节点同步生成 embedding，失败降级为 null）</li>
 *   <li>事务提交，返回 root_id + name + leaf_count</li>
 * </ol>
 *
 * <p>设计取舍：
 * <ul>
 *   <li>LLM 调用全部走 {@link LlmInvoker}（仓库 java-style.md "LLM 调用单一收口"）；本类只关心拼 vars + 解析 JSON</li>
 *   <li>embedding 同步生成（而非 Python 那样后批量回填）—— 简化事务边界；
 *       单个节点 embed 失败降级为 null 不阻断整树，与 S1 KnowledgeAdminServiceImpl 一致</li>
 *   <li>level 在递归里现算（base=1，每深一层 +1），与 S1 create() 单节点 level 推导一致</li>
 *   <li>nodeType：有 children → category，否则 leaf（与 S1 "默认 leaf 后升级" 路径不同 ——
 *       树生成时整棵都知道形状，可一次定型，无需事后升级）</li>
 * </ul>
 *
 * <p>用户：一期写死 user_id=1（CONVENTIONS §1，多用户在 S9 引入）。
 */
@Service
public class TreeGenServiceImpl implements TreeGenService {

    private static final Logger log = LoggerFactory.getLogger(TreeGenServiceImpl.class);
    private static final int LLM_RETRY = 3;
    private static final short DEFAULT_WEIGHT = 3;
    /** LLM 单次最大输出 token；Python 端 8192，避免大树被截断。 */
    private static final int LLM_MAX_TOKENS = 8192;
    /** 文本解析温度（与 Python create_tree_from_text 对齐）：低温防止 LLM 改写原文。 */
    private static final double TEMP_PARSE_TEXT = 0.1;
    /** 截图视觉解析温度（与 Python create_tree_from_image 对齐）：低温严守原文。 */
    private static final double TEMP_PARSE_IMAGE = 0.1;
    /** 树生成温度（与 Python create_tree_from_generate 对齐）：中温保留发散性。 */
    private static final double TEMP_GENERATE = 0.3;
    /** 语义去重温度（与 Python _check_duplicate_by_name 对齐）：零温要确定答案。 */
    private static final double TEMP_DUP_CHECK = 0.0;

    private final KnowledgeNodeMapper repo;
    private final UserMapper userMapper;
    private final EmbeddingService embeddingService;
    private final LlmInvoker llmInvoker;
    private final QwenVisionClient visionClient;
    private final PromptService promptService;

    public TreeGenServiceImpl(KnowledgeNodeMapper repo,
                              UserMapper userMapper,
                              EmbeddingService embeddingService,
                              LlmInvoker llmInvoker,
                              QwenVisionClient visionClient,
                              PromptService promptService) {
        this.repo = repo;
        this.userMapper = userMapper;
        this.embeddingService = embeddingService;
        this.llmInvoker = llmInvoker;
        this.visionClient = visionClient;
        this.promptService = promptService;
    }

    // ============================================================
    // 入口 1：from-text
    // ============================================================

    /**
     * 解析用户粘贴的文本（Markdown / 缩进列表）为知识树并落库。
     *
     * <p>LLM 只做"结构识别"——名称严格保持原文，不改写不归类（prompt 里有硬约束）。
     * <ol>
     *   <li>Step 1: 入参校验</li>
     *   <li>Step 2: 调 LLM（低温严守原文）→ TreeNodeJson</li>
     *   <li>Step 3: 树名去重</li>
     *   <li>Step 4: 递归落库 + embedding</li>
     * </ol>
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

        // Step 2: 调 LLM
        TreeNodeJson tree = callLlmJson(PromptKeys.TREE_PARSE_TEXT, Map.of("text", text),
                "from-text", TEMP_PARSE_TEXT, TreeNodeJson.class);

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
     * <ol>
     *   <li>Step 1: 入参校验 + requirements 回退</li>
     *   <li>Step 2: 先去重（避免白调 LLM）</li>
     *   <li>Step 3: 读用户画像</li>
     *   <li>Step 4: 调 LLM → TreeNodeJson</li>
     *   <li>Step 5: 补根名 + 递归落库</li>
     * </ol>
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

        // Step 3: 读用户画像（当前登录用户）；缺失时塞占位文案
        String profileText = userMapper.findProfileText(CurrentUser.id())
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .orElse("（未设置用户画像）");

        // Step 4: 调 LLM
        TreeNodeJson llmTree = callLlmJson(PromptKeys.TREE_GENERATE, Map.of(
                "tree_name", treeName,
                "requirements", requirements,
                "profile_text", profileText
        ), "from-generate", TEMP_GENERATE, TreeNodeJson.class);

        // Step 5: LLM 不返回根名，用 treeName 包装一层作为根；递归落库
        TreeNodeJson root = new TreeNodeJson(treeName, llmTree.children(), null);
        SaveResult sr = saveRecursive(root, null, (short) 1, List.of());
        log.info("[TreeGen] from-generate done root={} name='{}' leaves={}", sr.rootId, treeName, sr.leafCount);
        return new TreeGenResp(sr.rootId, treeName, sr.leafCount);
    }

    // ============================================================
    // 入口 3：from-image（截图 → qwen-vl 视觉解析）
    // ============================================================

    /**
     * 上传截图（思维导图 / 大纲）→ 多模态 LLM 视觉识别为知识树并落库。
     *
     * <p>与文本入口的差异仅在「取数据」一步：这里走 {@link QwenVisionClient}（DashScope qwen-vl-max），
     * prompt 直接 {@link PromptService#load} 取原文（无占位符，不渲染）。拿到 JSON 后的去重 / 落库
     * 与 from-text 完全一致。视觉调用最多重试 {@value #LLM_RETRY} 次。
     *
     * @param imageBase64 图片 base64（不含 data: 前缀）
     * @param mediaType   图片 MIME（如 image/png）
     * @throws BizException 40901 同名树已存在；50000 视觉解析失败
     */
    @Override
    @Transactional
    public TreeGenResp createFromImage(String imageBase64, String mediaType) {
        // Step 1: 取视觉 prompt（无占位符，load 原文即可）
        String prompt = promptService.load(PromptKeys.TREE_PARSE_IMAGE);

        // Step 2: 调视觉模型，重试 + 容错抽 JSON
        TreeNodeJson tree = null;
        for (int attempt = 1; attempt <= LLM_RETRY; attempt++) {
            try {
                String raw = visionClient.parseImage(imageBase64, mediaType, prompt, TEMP_PARSE_IMAGE);
                tree = JsonUtil.extractJson(raw, TreeNodeJson.class);
                if (tree != null) {
                    break;
                }
            } catch (Exception e) {
                log.warn("[TreeGen] from-image 第{}/{}次解析失败: {}", attempt, LLM_RETRY, e.getMessage());
            }
        }
        if (tree == null) {
            throw new BizException(50000, "截图解析失败，请重试");
        }

        // Step 3: 去重
        String treeName = tree.name() == null ? "" : tree.name().strip();
        if (!treeName.isEmpty()) {
            checkDuplicateByName(treeName);
        }

        // Step 4: 递归落库
        SaveResult sr = saveRecursive(tree, null, (short) 1, List.of());
        log.info("[TreeGen] from-image done root={} name='{}' leaves={}", sr.rootId, treeName, sr.leafCount);
        return new TreeGenResp(sr.rootId, treeName, sr.leafCount);
    }

    // ============================================================
    // 入口 4：from-mm（FreeMind/幕布 .mm 文件 → XML 解析，无 LLM）
    // ============================================================

    /**
     * 导入 FreeMind / 幕布导出的 .mm（XML）思维导图，直接映射为知识节点。
     *
     * <p>纯结构搬运、不走 LLM：按 {@code <node TEXT="...">} 的嵌套递归建树，所有节点
     * interview_weight 统一给 3（与 Python {@code _parse_mm_node} 对齐）。根节点 TEXT 为空时
     * 兜底命名「导入的知识树」。
     *
     * <p>安全：XML 解析关闭 DTD / 外部实体（防 XXE）。
     *
     * @param content .mm 文件原始字节
     * @throws BizException 40901 同名树已存在；50000 XML 非法 / 无有效节点
     */
    @Override
    @Transactional
    public TreeGenResp createFromMm(byte[] content) {
        // Step 1: 安全解析 XML，定位根 <node>
        Element rootNodeEl = parseMmRootNode(content);

        // Step 2: 递归把 XML 转成 TreeNodeJson
        TreeNodeJson tree = parseMmNode(rootNodeEl);

        // Step 3: 根名兜底
        String name = tree.name() == null ? "" : tree.name().strip();
        if (name.isEmpty()) {
            name = "导入的知识树";
            tree = new TreeNodeJson(name, tree.children(), tree.interviewWeight());
        }

        // Step 4: 去重 + 落库
        checkDuplicateByName(name);
        SaveResult sr = saveRecursive(tree, null, (short) 1, List.of());
        log.info("[TreeGen] from-mm done root={} name='{}' leaves={}", sr.rootId, name, sr.leafCount);
        return new TreeGenResp(sr.rootId, name, sr.leafCount);
    }

    /**
     * 安全解析 .mm 字节流，返回根 {@code <node>} 元素。
     *
     * <p>FreeMind 顶层是 {@code <map>} 包一个根 {@code <node>}；幕布等也可能直接给 {@code <node>}。
     * 两种都兼容：文档根若本身是 node 直接用，否则取文档里第一个 {@code <node>}（document order
     * 下即最外层那个）。
     */
    private Element parseMmRootNode(byte[] content) {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            // 关闭 DTD / 外部实体，防 XXE（.mm 是纯结构 XML，无需任何外部解析能力）
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
            dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            dbf.setXIncludeAware(false);
            dbf.setExpandEntityReferences(false);
            DocumentBuilder builder = dbf.newDocumentBuilder();

            Element docRoot = builder.parse(new ByteArrayInputStream(content)).getDocumentElement();
            if (docRoot == null) {
                throw new BizException(50000, ".mm 文件中未找到有效节点");
            }
            if ("node".equalsIgnoreCase(docRoot.getTagName())) {
                return docRoot;
            }
            NodeList nodes = docRoot.getElementsByTagName("node");
            for (int i = 0; i < nodes.getLength(); i++) {
                if (nodes.item(i) instanceof Element el) {
                    return el;
                }
            }
            throw new BizException(50000, ".mm 文件中未找到有效节点");
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(50000, ".mm 文件解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * 递归把 .mm 的 {@code <node>} 元素转成 {@link TreeNodeJson}。
     *
     * <p>只读直接子 {@code <node>}；TEXT 属性即节点名。空名且无子的节点会在上层被过滤
     * （与 Python {@code if parsed["name"] or parsed.get("children")} 一致）。
     */
    private TreeNodeJson parseMmNode(Element element) {
        String text = element.getAttribute("TEXT");
        text = text == null ? "" : text.strip();

        List<TreeNodeJson> children = new ArrayList<>();
        NodeList kids = element.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n instanceof Element el && "node".equalsIgnoreCase(el.getTagName())) {
                TreeNodeJson parsed = parseMmNode(el);
                boolean hasName = parsed.name() != null && !parsed.name().isEmpty();
                if (hasName || !parsed.isLeaf()) {
                    children.add(parsed);
                }
            }
        }
        return new TreeNodeJson(text, children.isEmpty() ? null : children, (short) 3);
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
        long userId = CurrentUser.id();
        long newId = (embeddingLiteral == null)
                ? repo.insertWithoutEmbedding(userId, parentId, name, level, nodeType, weight, 0, false)
                : repo.insertWithEmbedding(userId, parentId, name, level, nodeType, weight, 0, false, embeddingLiteral);

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
            repo.updateSortOrder(childRes.rootId, CurrentUser.id(), i);
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
    // 内部：LLM 调用 + JSON 解析
    // ============================================================

    /**
     * 调 LLM 拿结构化 JSON 的薄封装：组 Spec + 反序列化；失败抛 50000。
     * <p>parser 用 {@link JsonUtil#extractJson} 容错抽 JSON——即使 LLM 在 JSON 外塞了寒暄语，
     * 也能从代码围栏 / 括号配对里抠出来；抽不到 / 反序列化失败抛异常 → LlmInvoker 自动进入下一轮。
     *
     * @param scene 日志场景标签（如 "from-text"），便于排查（LlmInvoker 日志带 prompt key 已够，本参数仅用于异常 message）
     */
    private <T> T callLlmJson(String promptKey, Map<String, Object> vars,
                              String scene, double temperature, Class<T> type) {
        LlmInvoker.Spec spec = new LlmInvoker.Spec(promptKey, vars, temperature, LLM_MAX_TOKENS, LLM_RETRY);
        return llmInvoker.invoke(spec, raw -> JsonUtil.extractJson(raw, type))
                .orElseThrow(() -> new BizException(50000, "LLM 调用失败（" + scene + "）"));
    }

    // ============================================================
    // 内部：树名去重（精确 + LLM 语义两层）
    // ============================================================

    /**
     * 创建树前的同名检查（两层）：
     * <ol>
     *   <li>Step 1: 拉所有根节点</li>
     *   <li>Step 2: 精确匹配（case-insensitive，trim 后比较）—— 命中即抛 40901</li>
     *   <li>Step 3: LLM 语义匹配 —— 让 LLM 判断新名是否与某已有根节点同主题；命中抛 40901</li>
     * </ol>
     * <p>第二层 LLM 失败时跳过不阻断（与 Python 一致 —— LLM 不可用不应该阻止合法创建）。
     * LlmInvoker 返 empty 即视为不可用，直接 return。
     *
     * @throws BizException 40901 重复
     */
    private void checkDuplicateByName(String name) {
        // Step 1: 拉所有根节点
        List<KnowledgeNode> roots = repo.findRoots(CurrentUser.id());
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

        // Step 3: LLM 语义匹配（失败不阻断 → Optional.empty 直接退出）
        List<String> existingNames = roots.stream()
                .map(r -> r.name() == null ? "" : r.name().strip())
                .filter(s -> !s.isEmpty())
                .toList();
        if (existingNames.isEmpty()) {
            return;
        }
        String namesText = existingNames.stream().map(n -> "- " + n).collect(Collectors.joining("\n"));
        LlmInvoker.Spec spec = new LlmInvoker.Spec(PromptKeys.TREE_DUPLICATE_CHECK, Map.of(
                "new_name", name,
                "existing_names", namesText
        ), TEMP_DUP_CHECK, LLM_MAX_TOKENS, LLM_RETRY);
        DuplicateCheckResult res = llmInvoker.invoke(spec, raw -> JsonUtil.extractJson(raw, DuplicateCheckResult.class))
                .orElse(null);
        if (res == null || !Boolean.TRUE.equals(res.duplicate()) || res.matchedName() == null) {
            return;
        }
        String matched = res.matchedName().strip().toLowerCase(Locale.ROOT);
        for (KnowledgeNode r : roots) {
            String rn = r.name() == null ? "" : r.name().strip();
            if (rn.toLowerCase(Locale.ROOT).equals(matched)) {
                throw new BizException(40901, "与已有知识树「" + r.name() + "」语义重复，请更换名称或合并");
            }
        }
    }

    /** LLM tree/duplicate-check 的输出结构。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record DuplicateCheckResult(
            Boolean duplicate,
            @JsonProperty("matched_name") String matchedName
    ) {}
}
