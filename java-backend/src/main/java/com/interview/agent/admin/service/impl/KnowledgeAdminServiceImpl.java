package com.interview.agent.admin.service.impl;

import com.interview.agent.admin.dto.BatchSortReq;
import com.interview.agent.admin.dto.CreateKnowledgeNodeReq;
import com.interview.agent.admin.dto.DeleteNodeReq;
import com.interview.agent.admin.dto.KnowledgeNodeView;
import com.interview.agent.admin.dto.UpdateKnowledgeNodeReq;
import com.interview.agent.admin.service.KnowledgeAdminService;
import com.interview.agent.auth.CurrentUser;
import com.interview.agent.common.BizException;
import com.interview.agent.infra.llm.EmbeddingService;
import com.interview.agent.knowledge.mapper.KnowledgeNodeMapper;
import com.interview.agent.knowledge.entity.KnowledgeNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 知识树 Admin 服务 —— 节点 CRUD（S1）。
 *
 * 业务规则：
 *   1. 创建：有 parent → level = parent.level+1；新节点默认 nodeType='leaf'（不再按 level>=3 硬性判定）
 *   2. 创建：父节点原本是 leaf 时，升回 category（有了孩子就不是叶子了）
 *   3. 更新：仅当 movingParent=true 才改 parent_id 并重算自身 level；nodeType 按"有无子节点"重评
 *   4. 删除：先收集所有子孙 id → 清 FK 引用 → 批量 DELETE → 若父节点没娃了改 leaf
 *
 * 说明：知识树可深 3 层 / 4 层甚至更深（对比 project_node 硬性 3 层），
 *      所以 nodeType 由"有无子节点"决定，与 level 解耦。
 *
 * embedding 生成依赖 DashScope。若失败（如 dummy key）会降级为 null，
 * 节点照常创建，向量留待 S5 树生成 / 后续 backfill 补齐。
 */
@Service
public class KnowledgeAdminServiceImpl implements KnowledgeAdminService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeAdminServiceImpl.class);
    private static final short DEFAULT_INTERVIEW_WEIGHT = 3;

    private final KnowledgeNodeMapper repo;
    private final EmbeddingService embeddingService;

    public KnowledgeAdminServiceImpl(KnowledgeNodeMapper repo, EmbeddingService embeddingService) {
        this.repo = repo;
        this.embeddingService = embeddingService;
    }

    // ========== 查询 ==========

    /**
     * 列出全部知识节点（不分页，一次拉整棵树）。
     *
     * 返回顺序由 Mapper 保证：`ORDER BY parent_id NULLS FIRST, sort_order, id`，
     * 前端拿到后能按 parent 分组、同层按 sort_order 组装成树。
     * <p>
     * 不暴露 entity，映射为 {@link KnowledgeNodeView}（不含 embedding 向量列，避免传输浪费）。
     */
    @Override
    public List<KnowledgeNodeView> listAll() {
        // 1. 从 DB 拉整表（按 parent + sort_order 有序）
        // 2. entity → view，脱敏（去掉 embedding / created_at 等底层字段）
        return repo.findAllOrdered(CurrentUser.id()).stream()
                .map(n -> new KnowledgeNodeView(
                        n.id(), n.parentId(), n.name(), n.level(), n.nodeType(),
                        n.interviewWeight(), n.sortOrder()))
                .toList();
    }

    // ========== 创建 ==========

    /**
     * 创建一个知识节点。
     *
     * <p>完整逻辑：
     * <ol>
     *   <li>name 清洗：可以空（outliner UX）—— 前端按 Enter 先创建占位节点再 onBlur 填名字。</li>
     *   <li>根据 parentId 判定 level：有 parent → parent.level + 1，无 parent → 1（根节点）。</li>
     *   <li>新节点 nodeType 一律默认 leaf（与 level 解耦，不再按 level≥3 硬性判定）。</li>
     *   <li>调 EmbeddingService 生成 name 的向量字面量（空 name 跳过）；失败降级为 null。</li>
     *   <li>按是否有 embedding 选两个 Mapper 方法之一 INSERT，拿回新 id。</li>
     *   <li>若父节点原本是 leaf，升回 category（与删除后“没娃变 leaf”逻辑对称）。</li>
     * </ol>
     *
     * <p>全程 @Transactional：INSERT + 父节点 nodeType 更新要么同时成功要么同时回滚。
     *
     * @return 只返回必要字段：{id, name, level}（Controller 拿去拼 ApiResponse）
     * @throws BizException 40400 父节点不存在
     */
    @Override
    @Transactional
    public Map<String, Object> create(CreateKnowledgeNodeReq req) {
        // Step 1: 名称清洗 — 允许为空（outliner Enter 创建占位节点，onBlur 后走 update 填名字）
        String name = req.name() == null ? "" : req.name().strip();

        // Step 2: 查父节点 → 推导 level（根节点 level=1）
        short level;
        KnowledgeNode parent = null;
        if (req.parentId() != null) {
            parent = repo.findById(req.parentId())
                    .orElseThrow(() -> new BizException(40400, "父节点不存在"));
            level = (short) (parent.level() + 1);
        } else {
            level = 1;
        }

        // Step 3: 新节点默认 leaf；一旦被加子节点会被升为 category（下面 Step 6 反向依赖这个默认值）
        String nodeType = "leaf";
        short weight = req.interviewWeight() != null ? req.interviewWeight() : DEFAULT_INTERVIEW_WEIGHT;

        // Step 4: 同步生成 embedding（需要调 DashScope）；失败降级为 null，不阻断创建。
        //         name 为空时跳过向量生成（outliner 创建占位场景），onBlur 调 update 后可由 backfill 补
        String embeddingLiteral = name.isEmpty() ? null : safeEmbed(name);

        // Step 5: INSERT（两个 SQL 变体：带 / 不带 embedding）—— 避免 MyBatis 绑 NULL 到 ::vector 出错
        long userId = CurrentUser.id();
        long newId = (embeddingLiteral == null)
                ? repo.insertWithoutEmbedding(userId, req.parentId(), name, level, nodeType, weight, 0, false)
                : repo.insertWithEmbedding(userId, req.parentId(), name, level, nodeType, weight, 0, false, embeddingLiteral);

        // Step 6: 父节点升级——原本是 leaf 且现在多了个孩子 → 升为 category
        if (parent != null && "leaf".equals(parent.nodeType())) {
            repo.updateNodeType(parent.id(), userId, "category");
        }

        log.info("[KnowledgeAdmin] create id={} name='{}' level={} parent={}", newId, name, level, req.parentId());
        return Map.of("id", newId, "name", name, "level", (int) level);
    }

    // ========== 更新 ==========

    /**
     * 更新节点：基础字段（name / interviewWeight / sortOrder）+ 可选“移动父节点”。
     *
     * <p>完整逻辑：
     * <ol>
     *   <li>查出节点确认存在（不存在报 40400）。</li>
     *   <li>updateBasic：对 name / weight / sortOrder 使用 COALESCE，null 表示不改（部分更新）。</li>
     *   <li>只在显式 movingParent=true 时才动 parent_id。这是为了区分“不传 parentId”跟“显式传 null设为根”。</li>
     *   <li>移动时防环：不允许把节点挂到自己下面（未检查更深环，靠 schema parent 存在性兑现）。</li>
     *   <li>重算 newLevel = 新父.level + 1（或 1）。</li>
     *   <li>nodeType 按 hasChildren 重评 —— 子树不变，但“是否仍是叶子”要看自身是否还有娃。</li>
     * </ol>
     *
     * <p>注：<b>不会递归重算子树 level</b>（与 Python 端保持一致）。若需要全重算 P1 再补。
     *
     * @throws BizException 40400 节点 / 新父节点不存在；40001 挂到自己下面
     */
    @Override
    @Transactional
    public Map<String, Object> update(UpdateKnowledgeNodeReq req) {
        // Step 0: id 从 body 读（不再走 PathVariable）
        long id = req.id();
        // Step 1: 存在性检查（后面还要用 node.name() 做返回默认值）
        KnowledgeNode node = repo.findById(id)
                .orElseThrow(() -> new BizException(40400, "节点不存在"));

        // Step 2: 部分更新基础字段（Mapper 里用 COALESCE，null 保持原值）
        long userId = CurrentUser.id();
        String newName = req.name() == null ? null : req.name().strip();
        repo.updateBasic(id, userId, newName, req.interviewWeight(), req.sortOrder());

        // Step 3: 仅在显式 movingParent=true 时才动 parent（避免“漏传 parentId”被误解为“挂到根”）
        if (req.isMovingParent()) {
            // Step 3.1: 防环与重算 level
            short newLevel;
            if (req.parentId() != null) {
                if (req.parentId() == id) {
                    throw new BizException(40001, "不能把节点挂到自己下面");
                }
                KnowledgeNode newParent = repo.findById(req.parentId())
                        .orElseThrow(() -> new BizException(40400, "新父节点不存在"));
                newLevel = (short) (newParent.level() + 1);
            } else {
                newLevel = 1;
            }
            // Step 3.2: nodeType 按 "是否有子节点" 重评，与 level 解耦
            String newNodeType = repo.hasChildren(id) ? "category" : "leaf";
            // Step 3.3: 一句 SQL 同时改 parent / level / nodeType
            repo.moveParent(id, userId, req.parentId(), newLevel, newNodeType);
            // Step 3.4: 把整棵子树的 level 跟着平移 delta —— 否则前端按 level 算缩进会"打扁"
            //          （Python 端历史 bug：跨父移动一个有子节点的分类后，子孙仍保留旧 level）
            int delta = newLevel - node.level();
            if (delta != 0) {
                int shifted = repo.shiftDescendantLevels(id, userId, delta);
                log.info("[KnowledgeAdmin] move id={} -> parent={} level={}(delta={}) type={} subtree_shifted={}",
                        id, req.parentId(), newLevel, delta, newNodeType, shifted);
            } else {
                log.info("[KnowledgeAdmin] move id={} -> parent={} level={} type={}",
                        id, req.parentId(), newLevel, newNodeType);
            }
        }

        return Map.of("id", id, "name", newName != null ? newName : node.name());
    }

    // ========== 批量排序 ==========

    /**
     * 批量调整节点的 sort_order。
     *
     * <p>触发场景（来自前端 OutlinerEditor）——凡是会让兄弟序号需要重排的操作都会跟一发：
     * <ul>
     *   <li>回车在中间插入新节点 → 后面的兄弟全部 +1</li>
     *   <li>Tab 缩进 → 把自己挂到新父末尾（设新 sort_order）</li>
     *   <li>Shift+Tab 反缩进 → 升一层，后面叔伯整体后移</li>
     *   <li>拖拽 drop → 落到目标位置，后面兄弟 +1</li>
     * </ul>
     *
     * <p>本函数逐条 UPDATE（一次调用一般几条到十几条，同事务内性能没问题）。
     *
     * <p>不验证 id 是否同层——最多改错是 sortOrder 赋错位，不会破坏树结构；
     * 性能与严格性权衡下选了"信任前端"。
     *
     * @return {updated: 实际被改的行数}
     */
    @Override
    @Transactional
    public Map<String, Object> batchSort(BatchSortReq req) {
        // 逐条 UPDATE，累加影响行数（不存在的 id 返 0，不报错）
        long userId = CurrentUser.id();
        int count = 0;
        for (BatchSortReq.Item it : req.updates()) {
            count += repo.updateSortOrder(it.id(), userId, it.sortOrder());
        }
        return Map.of("updated", count);
    }

    // ========== 删除（递归） ==========

    /**
     * 递归删除节点及其全部子孙。
     *
     * <p>完整逻辑：
     * <ol>
     *   <li>查出待删节点，后面要用其 parentId。</li>
     *   <li>BFS 收集自身 + 所有子孙 id（避免递归 SQL / CTE，逻辑放 Java 可控）。</li>
     *   <li>手动清 FK：<br>
     *       — interview_knowledge_question.knowledge_node_id 需 SET NULL（保留面试记录）<br>
     *       — 其他如 study_question / knowledge_content 已是 ON DELETE CASCADE，不用管</li>
     *   <li>批量 DELETE 所有节点。</li>
     *   <li>如果删后父节点没娃了，把父从 category 降为 leaf（与 create 里“父从 leaf 升 category”对称）。</li>
     * </ol>
     *
     * <p>注：Mapper 中用 <code>&lt;foreach&gt;</code> 拼 IN 子句，<b>空集合会拼出非法 SQL</b>，
     * 所以调用前在 Service 层用 isEmpty() 护守。
     *
     * @return {deleted: 传入的根 id}（保留 Python 端返回格式）
     * @throws BizException 40400 节点不存在
     */
    @Override
    @Transactional
    public Map<String, Object> delete(DeleteNodeReq req) {
        // Step 0: id 从 body 读
        long id = req.id();
        // Step 1: 存在性检查，同时拿到 parentId 供最后“父降级”使用
        KnowledgeNode node = repo.findById(id)
                .orElseThrow(() -> new BizException(40400, "节点不存在"));

        // Step 2: BFS 收集自身 + 子孙 id（避免递归 SQL，逻辑集中在 Java）
        List<Long> allIds = collectDescendants(id);

        // Step 3: 清理非 CASCADE 的 FK 引用（空 List 会让 <foreach> 拼出非法 SQL，护守一下）
        if (!allIds.isEmpty()) {
            repo.nullOutInterviewKnowledgeRefs(allIds);
        }

        // Step 4: 批量 DELETE knowledge_node（CASCADE 表会自动跟走）
        long userId = CurrentUser.id();
        int deleted = allIds.isEmpty() ? 0 : repo.deleteByIds(userId, allIds);

        // Step 5: 父节点降级——现在没娃了 → 变 leaf（与 create 中“leaf 加孩 → category”对称）
        Long parentId = node.parentId();
        if (parentId != null && !repo.hasChildren(parentId)) {
            repo.updateNodeType(parentId, userId, "leaf");
        }

        log.info("[KnowledgeAdmin] delete id={} (cascade {} nodes)", id, deleted);
        return Map.of("deleted", id);
    }

    /**
     * BFS 收集以 rootId 为根的整棵子树（含根）的所有 id。
     *
     * <p>为什么不用 PG 递归 CTE：可读性与可测试性，Java 控制环、可拓展（如后续需要过滤某类型）。
     * 层次最多 4–5 层 × 每层十几个子 → SQL 调用量在十位数，代价可接受。
     *
     * <p>用 LinkedHashSet 同时满足：<br>
     * (1) <b>防环</b> —— 万一 schema 出现环状 parent（理论上不允许）不会死循环；<br>
     * (2) <b>保设计插入顺序</b> —— 调试时能看到“先遇到哪个”。
     */
    private List<Long> collectDescendants(long rootId) {
        // 初始化：结果集与本轮待展开的 frontier 都从 root 开始
        Set<Long> all = new LinkedHashSet<>();
        all.add(rootId);
        List<Long> frontier = new ArrayList<>(List.of(rootId));

        // BFS：一轮查当前层所有节点的直接子节点，去重后作为下一轮的 frontier
        while (!frontier.isEmpty()) {
            List<Long> next = new ArrayList<>();
            for (Long pid : frontier) {
                for (Long cid : repo.findChildIds(pid)) {
                    if (all.add(cid)) {   // add() 返 false 表示已存在 → 跳过（防环）
                        next.add(cid);
                    }
                }
            }
            frontier = next;
        }
        return new ArrayList<>(all);
    }

    // ========== 内部 ==========

    /**
     * 安全生成 embedding 字面量（形如 <code>'[v1,v2,...]'</code>）。
     *
     * <p>DashScope 不可用 / 配额超 / dummy key 等任何异常 → 返 null，Caller 改走
     * insertWithoutEmbedding 路径。后续可由 backfill 脚本 / S5 树生成补充向量，
     * <b>不阻断节点创建</b>（admin 手工加节点是高优先路径，向量是增量增强）。
     */
    private String safeEmbed(String text) {
        try {
            return embeddingService.embedToLiteral(text);
        } catch (Exception e) {
            log.warn("[KnowledgeAdmin] embedding failed, fallback to null: {}", e.getMessage());
            return null;
        }
    }
}
