package com.interview.agent.interview.exp.service.impl;

import com.interview.agent.admin.dto.BatchSortReq;
import com.interview.agent.admin.dto.DeleteNodeReq;
import com.interview.agent.auth.CurrentUser;
import com.interview.agent.common.BizException;
import com.interview.agent.infra.llm.EmbeddingService;
import com.interview.agent.interview.exp.dto.CreateInterviewExpNodeReq;
import com.interview.agent.interview.exp.dto.InterviewExpNodeView;
import com.interview.agent.interview.exp.dto.UpdateInterviewExpNodeReq;
import com.interview.agent.interview.exp.entity.InterviewExpNode;
import com.interview.agent.interview.exp.mapper.InterviewExpNodeMapper;
import com.interview.agent.interview.exp.service.InterviewExpAdminService;
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
 * 面经树 Admin 实现 —— 节点 CRUD。
 *
 * <p>面经树两层：level=1 → {@code domain}（知识域），level≥2 → {@code question}（问题）。
 * node_type 直接由 level 推导（与知识树"按有无子节点判 leaf"不同——面经层级固定、语义清晰）。
 *
 * <p>embedding：域名/问题名向量化，失败降级 null 不阻断创建（对齐知识树 admin）。
 */
@Service
public class InterviewExpAdminServiceImpl implements InterviewExpAdminService {

    private static final Logger log = LoggerFactory.getLogger(InterviewExpAdminServiceImpl.class);

    private final InterviewExpNodeMapper repo;
    private final EmbeddingService embeddingService;

    public InterviewExpAdminServiceImpl(InterviewExpNodeMapper repo, EmbeddingService embeddingService) {
        this.repo = repo;
        this.embeddingService = embeddingService;
    }

    /** level=1 → domain（知识域）；否则 question（问题）。 */
    private static String nodeTypeForLevel(short level) {
        return level <= 1 ? "domain" : "question";
    }

    // ========== 查询 ==========

    @Override
    public List<InterviewExpNodeView> listAll() {
        return repo.findAllOrdered(CurrentUser.id());
    }

    // ========== 创建 ==========

    @Override
    @Transactional
    public Map<String, Object> create(CreateInterviewExpNodeReq req) {
        String name = req.parentId() == null && req.name() == null ? "" : (req.name() == null ? "" : req.name().strip());

        short level;
        if (req.parentId() != null) {
            InterviewExpNode parent = repo.findById(req.parentId(), CurrentUser.id())
                    .orElseThrow(() -> new BizException(40400, "父节点不存在"));
            level = (short) (parent.level() + 1);
        } else {
            level = 1;
        }
        String nodeType = nodeTypeForLevel(level);

        String embeddingLiteral = name.isEmpty() ? null : safeEmbed(name);
        long userId = CurrentUser.id();
        long newId = (embeddingLiteral == null)
                ? repo.insertWithoutEmbedding(userId, req.parentId(), name, level, nodeType, 0)
                : repo.insertWithEmbedding(userId, req.parentId(), name, level, nodeType, 0, embeddingLiteral);

        log.info("[InterviewExpAdmin] create id={} name='{}' level={} parent={}", newId, name, level, req.parentId());
        return Map.of("id", newId, "name", name, "level", (int) level);
    }

    // ========== 更新 ==========

    @Override
    @Transactional
    public Map<String, Object> update(UpdateInterviewExpNodeReq req) {
        long id = req.id();
        long userId = CurrentUser.id();
        InterviewExpNode node = repo.findById(id, userId)
                .orElseThrow(() -> new BizException(40400, "节点不存在"));

        String newName = req.name() == null ? null : req.name().strip();
        repo.updateBasic(id, userId, newName, req.sortOrder());

        if (req.isMovingParent()) {
            short newLevel;
            if (req.parentId() != null) {
                if (req.parentId() == id) {
                    throw new BizException(40001, "不能把节点挂到自己下面");
                }
                InterviewExpNode newParent = repo.findById(req.parentId(), userId)
                        .orElseThrow(() -> new BizException(40400, "新父节点不存在"));
                newLevel = (short) (newParent.level() + 1);
            } else {
                newLevel = 1;
            }
            repo.moveParent(id, userId, req.parentId(), newLevel, nodeTypeForLevel(newLevel));
            int delta = newLevel - node.level();
            if (delta != 0) {
                int shifted = repo.shiftDescendantLevels(id, userId, delta);
                log.info("[InterviewExpAdmin] move id={} -> parent={} level={}(delta={}) subtree_shifted={}",
                        id, req.parentId(), newLevel, delta, shifted);
            }
        }
        return Map.of("id", id, "name", newName != null ? newName : node.name());
    }

    // ========== 批量排序 ==========

    @Override
    @Transactional
    public Map<String, Object> batchSort(BatchSortReq req) {
        long userId = CurrentUser.id();
        int count = 0;
        for (BatchSortReq.Item it : req.updates()) {
            count += repo.updateSortOrder(it.id(), userId, it.sortOrder());
        }
        return Map.of("updated", count);
    }

    // ========== 删除（递归） ==========

    @Override
    @Transactional
    public Map<String, Object> delete(DeleteNodeReq req) {
        long id = req.id();
        long userId = CurrentUser.id();
        repo.findById(id, userId).orElseThrow(() -> new BizException(40400, "节点不存在"));

        List<Long> allIds = collectDescendants(id);
        int deleted = allIds.isEmpty() ? 0 : repo.deleteByIds(userId, allIds);

        log.info("[InterviewExpAdmin] delete id={} (cascade {} nodes)", id, deleted);
        return Map.of("deleted", id);
    }

    @Override
    @Transactional
    public Map<String, Object> deleteChildren(DeleteNodeReq req) {
        long id = req.id();
        long userId = CurrentUser.id();
        repo.findById(id, userId).orElseThrow(() -> new BizException(40400, "节点不存在"));

        List<Long> childIds = collectDescendants(id);
        childIds.remove(Long.valueOf(id));
        int deleted = childIds.isEmpty() ? 0 : repo.deleteByIds(userId, childIds);

        log.info("[InterviewExpAdmin] deleteChildren id={} (removed {} descendants)", id, deleted);
        return Map.of("id", id, "deleted", deleted);
    }

    // ========== 内部 ==========

    /** BFS 收集以 rootId 为根的整棵子树（含根）的所有 id；LinkedHashSet 防环。 */
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

    private String safeEmbed(String text) {
        try {
            return embeddingService.embedToLiteral(text);
        } catch (Exception e) {
            log.warn("[InterviewExpAdmin] embedding failed, fallback to null: {}", e.getMessage());
            return null;
        }
    }
}
