package com.interview.agent.admin.service.impl;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.interview.agent.admin.dto.BatchSortReq;
import com.interview.agent.admin.dto.CreateProjectNodeReq;
import com.interview.agent.admin.dto.DeleteNodeReq;
import com.interview.agent.admin.dto.ProjectFromTextReq;
import com.interview.agent.admin.dto.ProjectFromTextResp;
import com.interview.agent.admin.dto.ProjectNodeView;
import com.interview.agent.admin.dto.TreeNodeJson;
import com.interview.agent.admin.dto.UpdateProjectNodeReq;
import com.interview.agent.admin.service.ProjectAdminService;
import com.interview.agent.auth.CurrentUser;
import com.interview.agent.common.BizException;
import com.interview.agent.common.JsonUtil;
import com.interview.agent.common.LlmInvoker;
import com.interview.agent.infra.llm.EmbeddingService;
import com.interview.agent.project.entity.ProjectNode;
import com.interview.agent.project.mapper.InterviewProjectQuestionMapper;
import com.interview.agent.project.mapper.ProjectMapper;
import com.interview.agent.project.mapper.ProjectNodeMapper;
import com.interview.agent.prompts.PromptKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 项目树 Admin 服务实现（S6）—— 节点 CRUD + from-text。
 *
 * <p>与 S1 KnowledgeAdminServiceImpl 平行，关键差异（S6 doc §0 决策 4/9）：
 * <ul>
 *   <li><b>level/node_type 硬规则</b>：项目树固定三层。{@code node_type = level >= 3 ? 'leaf' : 'category'}。
 *       与 S1 "按有无子节点定 nodeType" 不同（知识树深度可变）。</li>
 *   <li><b>from-text 同步建 project 行</b>：name=root.name / description=raw_text / root_node_id=root.id。
 *       这是用户视角"项目"的<b>唯一创建入口</b>（拷打页只查/编辑，不创建）。</li>
 * </ul>
 *
 * <p>用户：一期写死 user_id=1。
 */
@Service
public class ProjectAdminServiceImpl implements ProjectAdminService {

    private static final Logger log = LoggerFactory.getLogger(ProjectAdminServiceImpl.class);

    /** 项目树固定三层；超过此 level 的节点强制 leaf。 */
    private static final int MAX_LEVEL = 3;

    private static final int LLM_RETRY = 3;
    private static final int LLM_MAX_TOKENS = 8192;
    /** 文本解析温度（与 Python 对齐）：低温防止 LLM 改写原文。 */
    private static final double TEMP_PARSE_TEXT = 0.1;
    /** 语义去重温度：零温要确定答案。 */
    private static final double TEMP_DUP_CHECK = 0.0;

    private final ProjectNodeMapper repo;
    private final ProjectMapper projectRepo;
    private final InterviewProjectQuestionMapper interviewProjectQuestionMapper;
    private final EmbeddingService embeddingService;
    private final LlmInvoker llmInvoker;

    public ProjectAdminServiceImpl(ProjectNodeMapper repo,
                                   ProjectMapper projectRepo,
                                   InterviewProjectQuestionMapper interviewProjectQuestionMapper,
                                   EmbeddingService embeddingService,
                                   LlmInvoker llmInvoker) {
        this.repo = repo;
        this.projectRepo = projectRepo;
        this.interviewProjectQuestionMapper = interviewProjectQuestionMapper;
        this.embeddingService = embeddingService;
        this.llmInvoker = llmInvoker;
    }

    // ============================================================
    // 查询
    // ============================================================

    @Override
    public List<ProjectNodeView> listAll() {
        return repo.findAllOrdered(CurrentUser.id()).stream()
                .map(n -> new ProjectNodeView(
                        n.id(), n.parentId(), n.name(), n.level(), n.nodeType(), n.sortOrder()))
                .toList();
    }

    // ============================================================
    // 创建
    // ============================================================

    /**
     * 创建项目节点。逻辑同 S1，但 nodeType 用<b>硬规则</b>而非"有无子节点"。
     * <ol>
     *   <li>校验 name 非空；查父节点（若有）→ 推导 level（无父=1）</li>
     *   <li>nodeType = level >= MAX_LEVEL ? leaf : category（项目树硬规则）</li>
     *   <li>safeEmbed("祖先链 / name") → 失败降级 null</li>
     *   <li>INSERT（带/不带 embedding 双 SQL）</li>
     * </ol>
     *
     * <p>与 S1 差异：项目树<b>不</b>做"父 leaf→category"自动升级 —— nodeType 严格按 level 派生，
     * 与 Python project_node.create_node 一致。
     */
    @Override
    @Transactional
    public Map<String, Object> create(CreateProjectNodeReq req) {
        // Step 1: 名称清洗 — 允许为空（outliner Enter 创建占位节点，onBlur 后走 update 填名字）
        String name = req.name() == null ? "" : req.name().strip();

        // Step 2: 查父 + 推 level + 收集祖先 path（embedding 文本用）
        short level;
        List<String> ancestors;
        if (req.parentId() != null) {
            ProjectNode parent = repo.findById(req.parentId(), CurrentUser.id())
                    .orElseThrow(() -> new BizException(40400, "父节点不存在"));
            level = (short) (parent.level() + 1);
            // 项目树固定三层硬限：禁止在 level=3 节点下再加子节点（防拖拉拽破坏结构）
            if (level > MAX_LEVEL) {
                throw new BizException(40001,
                        "项目树最多 " + MAX_LEVEL + " 层（项目→话题→问题），不能在第 " + parent.level() + " 层节点下继续添加子节点");
            }
            ancestors = collectAncestorNames(parent);
        } else {
            level = 1;
            ancestors = List.of();
        }

        // Step 3: 项目树 node_type 由 level 决定（1=project / 2=topic / 3=question）
        String nodeType = projectNodeType(level);

        // Step 4: embedding 文本 = 祖先链 / 当前 name（空 name 跳过）
        String embedText = ancestors.isEmpty() ? name : String.join(" / ", ancestors) + " / " + name;
        String embeddingLiteral = name.isEmpty() ? null : safeEmbed(embedText);

        // Step 5: INSERT
        long newId = (embeddingLiteral == null)
                ? repo.insertWithoutEmbedding(CurrentUser.id(), req.parentId(), name, level, nodeType, 0)
                : repo.insertWithEmbedding(CurrentUser.id(), req.parentId(), name, level, nodeType, 0, embeddingLiteral);

        log.info("[ProjectAdmin] create id={} name='{}' level={} parent={}", newId, name, level, req.parentId());
        return Map.of("id", newId, "name", name, "level", (int) level);
    }

    // ============================================================
    // 更新
    // ============================================================

    /**
     * 部分更新。逻辑同 S1，但 movingParent 后 nodeType 走<b>硬规则</b>。
     * <p>不动 embedding（doc §0 决策 12：改名不重算，简化）。
     */
    @Override
    @Transactional
    public Map<String, Object> update(UpdateProjectNodeReq req) {
        // id 从 body 读（不再走 PathVariable）
        long id = req.id();
        ProjectNode node = repo.findById(id, CurrentUser.id())
                .orElseThrow(() -> new BizException(40400, "节点不存在"));

        // Step 1: 部分更新（COALESCE）
        String newName = req.name() == null ? null : req.name().strip();
        repo.updateBasic(id, CurrentUser.id(), newName, req.sortOrder());

        // Step 2: 仅 movingParent=true 才动 parent
        if (req.isMovingParent()) {
            short newLevel;
            if (req.parentId() != null) {
                if (req.parentId() == id) {
                    throw new BizException(40001, "不能把节点挂到自己下面");
                }
                ProjectNode newParent = repo.findById(req.parentId(), CurrentUser.id())
                        .orElseThrow(() -> new BizException(40400, "新父节点不存在"));
                newLevel = (short) (newParent.level() + 1);
            } else {
                newLevel = 1;
            }
            // 项目树固定三层硬限：移动后子树最深 level 不得超过 3
            // 计算方式：子树最深 level - 节点当前 level = 子树深度差；新最深 = newLevel + 子树深度差
            int currentMaxInSubtree = repo.findMaxLevelInSubtree(id, CurrentUser.id());
            int subtreeDepthBelow = currentMaxInSubtree - node.level();
            int newMaxAfterMove = newLevel + subtreeDepthBelow;
            if (newMaxAfterMove > MAX_LEVEL) {
                throw new BizException(40001,
                        "项目树最多 " + MAX_LEVEL + " 层（项目→话题→问题），此移动会导致子树深度达到第 "
                                + newMaxAfterMove + " 层");
            }
            // 项目树 node_type 直接由 level 决定
            String newNodeType = projectNodeType(newLevel);
            repo.moveParent(id, CurrentUser.id(), req.parentId(), newLevel, newNodeType);

            // 子树 level 平移（同时按硬规则重写 node_type，见 Mapper SQL）
            int delta = newLevel - node.level();
            if (delta != 0) {
                int shifted = repo.shiftDescendantLevels(id, CurrentUser.id(), delta);
                log.info("[ProjectAdmin] move id={} -> parent={} level={}(delta={}) type={} subtree_shifted={}",
                        id, req.parentId(), newLevel, delta, newNodeType, shifted);
            } else {
                log.info("[ProjectAdmin] move id={} -> parent={} level={} type={}",
                        id, req.parentId(), newLevel, newNodeType);
            }
        }

        return Map.of("id", id, "name", newName != null ? newName : node.name());
    }

    // ============================================================
    // 批量排序
    // ============================================================

    @Override
    @Transactional
    public Map<String, Object> batchSort(BatchSortReq req) {
        int count = 0;
        for (BatchSortReq.Item it : req.updates()) {
            count += repo.updateSortOrder(it.id(), CurrentUser.id(), it.sortOrder());
        }
        return Map.of("updated", count);
    }

    // ============================================================
    // 递归删除
    // ============================================================

    /**
     * 删自身 + 全部子孙。
     * <ol>
     *   <li>BFS 收集 nodeId 集合</li>
     *   <li>UPDATE interview_project_question SET project_node_id=NULL WHERE in (...) — 保留事实数据</li>
     *   <li>批量 DELETE（PG ON DELETE SET NULL 自动处理 project.root_node_id）</li>
     *   <li>父若没娃了 → 降回 leaf（仅当父 level&lt;3，level=3 本就 leaf）</li>
     * </ol>
     */
    @Override
    @Transactional
    public Map<String, Object> delete(DeleteNodeReq req) {
        // id 从 body 读
        long id = req.id();
        ProjectNode node = repo.findById(id, CurrentUser.id())
                .orElseThrow(() -> new BizException(40400, "节点不存在"));

        // Step 1: BFS 收集 + 防环
        List<Long> allIds = collectDescendants(id);

        // Step 2: 清 FK 引用（空集合护守）
        if (!allIds.isEmpty()) {
            interviewProjectQuestionMapper.nullOutByNodeIds(allIds);
        }

        // Step 3: 批量 DELETE（tree_node parent_id ON DELETE CASCADE 自动清子树）
        int deleted = allIds.isEmpty() ? 0 : repo.deleteByIds(allIds, CurrentUser.id());

        // 项目树 node_type 由 level 决定，不因子节点增删而变，无需父降级。

        log.info("[ProjectAdmin] delete id={} (cascade {} nodes)", id, deleted);
        return Map.of("deleted", id);
    }

    /**
     * 只删该节点的全部子孙、保留节点自身。
     *
     * <p>动机：节点拆得太碎时，一次清掉某节点下的一堆碎子项，让它自己收敛为一个叶子。
     * <p>项目树 node_type 由 level 决定，不因子节点增删而变，故无需改自身 node_type。
     *
     * @return {id: 保留的节点 id, deleted: 被删的子孙数量}
     * @throws BizException 40400 节点不存在
     */
    @Override
    @Transactional
    public Map<String, Object> deleteChildren(DeleteNodeReq req) {
        long id = req.id();
        repo.findById(id, CurrentUser.id())
                .orElseThrow(() -> new BizException(40400, "节点不存在"));

        // 收集整棵子树后移除根自身 → 仅剩子孙
        List<Long> childIds = collectDescendants(id);
        childIds.remove(Long.valueOf(id));

        // 清 FK 引用（interview_project_question.project_node_id）后批量删
        if (!childIds.isEmpty()) {
            interviewProjectQuestionMapper.nullOutByNodeIds(childIds);
        }
        int deleted = childIds.isEmpty() ? 0 : repo.deleteByIds(childIds, CurrentUser.id());

        log.info("[ProjectAdmin] deleteChildren id={} (removed {} descendants)", id, deleted);
        return Map.of("id", id, "deleted", deleted);
    }

    // ============================================================
    // from-text
    // ============================================================

    /**
     * 把项目描述拆为 项目→话题→问题 三层树。
     * <ol>
     *   <li>校验 text 非空</li>
     *   <li>调 LLM（低温保原文）→ TreeNodeJson</li>
     *   <li>项目名两层去重（精确 + LLM 语义）</li>
     *   <li>递归 saveTree（每层都生成 embedding）</li>
     *   <li>INSERT project 元数据行（name=root.name, description=raw_text, root_node_id=root.id）</li>
     * </ol>
     *
     * @throws BizException 40001 text 空；40901 项目名重复；50000 LLM 解析失败
     */
    @Override
    @Transactional
    public ProjectFromTextResp createFromText(ProjectFromTextReq req) {
        // Step 1: 入参校验
        String text = req.text() == null ? "" : req.text().strip();
        if (text.isEmpty()) {
            throw new BizException(40001, "项目描述不能为空");
        }

        // Step 2: 调 LLM
        LlmInvoker.Spec spec = new LlmInvoker.Spec(PromptKeys.PROJECT_PARSE_TEXT,
                Map.of("text", text), TEMP_PARSE_TEXT, LLM_MAX_TOKENS, LLM_RETRY);
        TreeNodeJson tree = llmInvoker.invoke(spec, raw -> JsonUtil.extractJson(raw, TreeNodeJson.class))
                .orElseThrow(() -> new BizException(50000, "LLM 项目解析失败"));

        String projectName = tree.name() == null ? "" : tree.name().strip();
        if (projectName.isEmpty()) {
            throw new BizException(50000, "LLM 返回项目名为空");
        }

        // Step 3: 项目名去重（精确 + LLM 语义）
        checkDuplicateByName(projectName);

        // Step 4: 递归落库 + embedding
        SaveResult sr = saveRecursive(tree, null, (short) 1, List.of());

        // Step 5: 同步建 project 元数据行
        long projectId = projectRepo.insertReturningId(CurrentUser.id(), projectName, text, sr.rootId);

        log.info("[ProjectAdmin] from-text done root={} project={} name='{}' leaves={}",
                sr.rootId, projectId, projectName, sr.leafCount);
        return new ProjectFromTextResp(sr.rootId, projectId, projectName, sr.leafCount);
    }

    // ============================================================
    // 内部：递归落库
    // ============================================================

    private record SaveResult(long rootId, int leafCount) {}

    /** 项目树 node_type 由 level 固定映射：1=project / 2=topic / 3=question。 */
    private static String projectNodeType(int level) {
        return level == 1 ? "project" : level == 2 ? "topic" : "question";
    }

    /**
     * 递归把 TreeNodeJson 写入 project_node。
     * <ul>
     *   <li>level≥3 强制 leaf 并停止递归（项目树硬规则；即使 LLM 给了 4 层也截断）</li>
     *   <li>level&lt;3 看 children：有娃→category 继续递归；无娃→leaf（合法的"空话题"）</li>
     *   <li>embedding 文本 = "祖先 / ... / 当前名"（与 S1 一致；含全部 level 节点）</li>
     *   <li>embed 失败 → null，节点仍入库</li>
     * </ul>
     */
    private SaveResult saveRecursive(TreeNodeJson node,
                                     Long parentId,
                                     short level,
                                     List<String> ancestors) {
        // Step 1: name 清洗
        String name = node.name() == null ? "" : node.name().strip();
        if (name.isEmpty()) {
            throw new BizException(50000, "LLM 返回的节点 name 为空");
        }

        // Step 2: 项目树硬规则 —— 到 MAX_LEVEL 停递归；node_type 由 level 决定
        boolean atMaxLevel = level >= MAX_LEVEL;
        boolean hasKids = !atMaxLevel && node.children() != null && !node.children().isEmpty();
        String nodeType = projectNodeType(level);

        // Step 3: embedding 文本（项目树全部 level 都生成 embedding —— doc §0 决策 5）
        String embedText = ancestors.isEmpty() ? name : String.join(" / ", ancestors) + " / " + name;
        String embeddingLiteral = safeEmbed(embedText);

        // Step 4: INSERT
        long newId = (embeddingLiteral == null)
                ? repo.insertWithoutEmbedding(CurrentUser.id(), parentId, name, level, nodeType, 0)
                : repo.insertWithEmbedding(CurrentUser.id(), parentId, name, level, nodeType, 0, embeddingLiteral);

        // Step 5: 终止条件 —— 叶子计 1
        if (!hasKids) {
            return new SaveResult(newId, 1);
        }

        // Step 6: 递归子节点；累加叶子；sort_order 按下标
        int leafCount = 0;
        List<String> nextAncestors = new ArrayList<>(ancestors.size() + 1);
        nextAncestors.addAll(ancestors);
        nextAncestors.add(name);
        List<TreeNodeJson> children = node.children();
        for (int i = 0; i < children.size(); i++) {
            SaveResult childRes = saveRecursive(children.get(i), newId, (short) (level + 1), nextAncestors);
            repo.updateSortOrder(childRes.rootId, CurrentUser.id(), i);
            leafCount += childRes.leafCount;
        }
        return new SaveResult(newId, leafCount);
    }

    // ============================================================
    // 内部：项目名去重（精确 + LLM 语义）
    // ============================================================

    private void checkDuplicateByName(String name) {
        List<ProjectNode> roots = repo.findRoots(CurrentUser.id());
        if (roots.isEmpty()) {
            return;
        }
        String lowered = name.toLowerCase(Locale.ROOT);

        // Step 1: 精确同名
        for (ProjectNode r : roots) {
            String rn = r.name() == null ? "" : r.name().strip();
            if (rn.toLowerCase(Locale.ROOT).equals(lowered)) {
                throw new BizException(40901, "已存在同名项目「" + r.name() + "」");
            }
        }

        // Step 2: LLM 语义匹配；失败不阻断
        List<String> existingNames = roots.stream()
                .map(r -> r.name() == null ? "" : r.name().strip())
                .filter(s -> !s.isEmpty())
                .toList();
        if (existingNames.isEmpty()) {
            return;
        }
        String namesText = existingNames.stream().map(n -> "- " + n).collect(Collectors.joining("\n"));
        LlmInvoker.Spec spec = new LlmInvoker.Spec(PromptKeys.PROJECT_DUP_CHECK, Map.of(
                "new_name", name,
                "existing_names", namesText
        ), TEMP_DUP_CHECK, LLM_MAX_TOKENS, LLM_RETRY);
        DuplicateCheckResult res = llmInvoker.invoke(spec, raw -> JsonUtil.extractJson(raw, DuplicateCheckResult.class))
                .orElse(null);
        if (res == null || !Boolean.TRUE.equals(res.duplicate()) || res.matchedName() == null) {
            return;
        }
        String matched = res.matchedName().strip().toLowerCase(Locale.ROOT);
        for (ProjectNode r : roots) {
            String rn = r.name() == null ? "" : r.name().strip();
            if (rn.toLowerCase(Locale.ROOT).equals(matched)) {
                throw new BizException(40901, "与已有项目「" + r.name() + "」语义重复");
            }
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record DuplicateCheckResult(
            Boolean duplicate,
            @JsonProperty("matched_name") String matchedName
    ) {}

    // ============================================================
    // 内部：BFS / 工具
    // ============================================================

    private List<Long> collectDescendants(long rootId) {
        Set<Long> all = new LinkedHashSet<>();
        all.add(rootId);
        List<Long> frontier = new ArrayList<>(List.of(rootId));
        while (!frontier.isEmpty()) {
            List<Long> next = new ArrayList<>();
            for (Long pid : frontier) {
                for (Long cid : repo.findChildIds(pid, CurrentUser.id())) {
                    if (all.add(cid)) {
                        next.add(cid);
                    }
                }
            }
            frontier = next;
        }
        return new ArrayList<>(all);
    }

    /** 沿 parent 链回溯出名字数组（含 parent 自身，按根→叶顺序）。 */
    private List<String> collectAncestorNames(ProjectNode parent) {
        List<String> chain = new ArrayList<>();
        ProjectNode cur = parent;
        while (cur != null) {
            chain.add(0, cur.name());
            if (cur.parentId() == null) break;
            cur = repo.findById(cur.parentId(), CurrentUser.id()).orElse(null);
        }
        return chain;
    }

    private String safeEmbed(String text) {
        try {
            return embeddingService.embedToLiteral(text);
        } catch (Exception e) {
            log.warn("[ProjectAdmin] embedding 失败，节点不带向量入库: text='{}' err={}", text, e.getMessage());
            return null;
        }
    }
}
