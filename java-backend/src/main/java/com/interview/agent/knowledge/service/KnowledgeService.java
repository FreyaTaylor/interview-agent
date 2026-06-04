package com.interview.agent.knowledge.service;

import com.interview.agent.knowledge.dto.KnowledgeTreeNodeView;
import com.interview.agent.knowledge.entity.KnowledgeNode;
import com.interview.agent.knowledge.mapper.KnowledgeNodeMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识树查询服务（S2）。
 *
 * 唯一职责：把 knowledge_node 平表组装成多层嵌套树。
 *
 * 排序由 SQL 端的 level → sort_order → id 完成，组装时只需一次顺序遍历：
 * 先建 id → 可变包装的映射，再在第二遍把每个节点挂到父节点的 children 上，
 * 时间复杂度 O(n)，比递归查询友好。
 *
 * mastery_level / study_count 从 knowledge_node 同名列直读（由 study/finish 钩子写入）。
 */
@Service
public class KnowledgeService {

    private final KnowledgeNodeMapper mapper;

    public KnowledgeService(KnowledgeNodeMapper mapper) {
        this.mapper = mapper;
    }

    public List<KnowledgeTreeNodeView> buildTree() {
        List<KnowledgeNode> all = mapper.findAllOrdered();

        // 用可变 List 暂存 children，最后整体作为 Record 字段返回
        Map<Long, List<KnowledgeTreeNodeView>> childrenBuckets = new HashMap<>(all.size() * 2);
        Map<Long, KnowledgeNode> nodeById = new HashMap<>(all.size() * 2);
        for (KnowledgeNode n : all) {
            childrenBuckets.put(n.id(), new ArrayList<>());
            nodeById.put(n.id(), n);
        }

        // 第一遍：把每个节点挂到父节点的 children bucket（根节点暂记到 roots bucket，key=null 用 -1 占位）
        List<KnowledgeTreeNodeView> roots = new ArrayList<>();
        for (KnowledgeNode n : all) {
            KnowledgeTreeNodeView view = toView(n, childrenBuckets.get(n.id()));
            Long parentId = n.parentId();
            if (parentId != null && childrenBuckets.containsKey(parentId)) {
                childrenBuckets.get(parentId).add(view);
            } else {
                roots.add(view);
            }
        }
        return roots;
    }

    private KnowledgeTreeNodeView toView(KnowledgeNode n, List<KnowledgeTreeNodeView> children) {
        return new KnowledgeTreeNodeView(
                n.id(),
                n.parentId(),
                n.name(),
                n.level(),
                n.nodeType(),
                n.interviewWeight(),
                n.sortOrder(),
                n.masteryLevel() == null ? 0 : n.masteryLevel().intValue(),
                n.studyCount(),
                children
        );
    }
}
