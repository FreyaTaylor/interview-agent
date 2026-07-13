package com.interview.agent.knowledge.service;

import com.interview.agent.auth.CurrentUser;
import com.interview.agent.knowledge.dto.KnowledgeTreeNodeView;
import com.interview.agent.knowledge.entity.KnowledgeNode;
import com.interview.agent.knowledge.mapper.KnowledgeNodeMapper;
import com.interview.agent.interview.mapper.InterviewQuestionKpLinkMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    private final InterviewQuestionKpLinkMapper linkMapper;

    public KnowledgeService(KnowledgeNodeMapper mapper, InterviewQuestionKpLinkMapper linkMapper) {
        this.mapper = mapper;
        this.linkMapper = linkMapper;
    }

    public List<KnowledgeTreeNodeView> buildTree() {
        // tree_node 里知识树含 category/knowledge_point/subtopic/question 四类；
        // 知识树视图只展示到"知识点"层，子话题/问题属学习页下钻，这里过滤掉；空名节点（占位/误建）也不展示。
        List<KnowledgeNode> all = mapper.findAllOrdered(CurrentUser.id()).stream()
                .filter(n -> "category".equals(n.nodeType()) || "knowledge_point".equals(n.nodeType()))
                .filter(n -> n.name() != null && !n.name().isBlank())
                .toList();

        // 命中过真题的知识点 id（打♥️徽标用）
        Set<Long> kpWithInterview = new HashSet<>(linkMapper.findLinkedKnowledgePointIds(CurrentUser.id()));

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
            KnowledgeTreeNodeView view = toView(n, childrenBuckets.get(n.id()), kpWithInterview.contains(n.id()));
            Long parentId = n.parentId();
            if (parentId != null && childrenBuckets.containsKey(parentId)) {
                childrenBuckets.get(parentId).add(view);
            } else {
                roots.add(view);
            }
        }
        return roots;
    }

    private KnowledgeTreeNodeView toView(KnowledgeNode n, List<KnowledgeTreeNodeView> children,
                                         boolean hasInterviewQuestions) {
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
                n.selfMastery() == null ? 0 : n.selfMastery().intValue(),
                children,
                null, null,   // category/knowledge_point 无 tier/source
                hasInterviewQuestions
        );
    }

    /**
     * 全量知识树（管理用）：分类 → 知识点 → 子话题 → 问题（含面试真题），可折叠 + 可操作。
     * 与 {@link #buildTree()} 区别：不过滤 node_type，问题节点带 tier/source。
     */
    public List<KnowledgeTreeNodeView> buildFullTree() {
        List<com.interview.agent.knowledge.dto.KnowledgeFullRow> all =
                mapper.findFullTree(CurrentUser.id()).stream()
                        .filter(r -> r.name() != null && !r.name().isBlank())
                        .toList();

        // 命中过真题的知识点 id（打♥️徽标用）
        Set<Long> kpWithInterview = new HashSet<>(linkMapper.findLinkedKnowledgePointIds(CurrentUser.id()));

        Map<Long, List<KnowledgeTreeNodeView>> buckets = new HashMap<>(all.size() * 2);
        for (var r : all) {
            buckets.put(r.id(), new ArrayList<>());
        }
        List<KnowledgeTreeNodeView> roots = new ArrayList<>();
        for (var r : all) {
            KnowledgeTreeNodeView view = new KnowledgeTreeNodeView(
                    r.id(), r.parentId(), r.name(), r.level(), r.nodeType(),
                    r.interviewWeight(), r.sortOrder(), r.masteryLevel(), 0, r.selfMastery(),
                    buckets.get(r.id()), r.tier(), r.source(),
                    kpWithInterview.contains(r.id()));
            Long pid = r.parentId();
            if (pid != null && buckets.containsKey(pid)) {
                buckets.get(pid).add(view);
            } else {
                roots.add(view);
            }
        }
        return roots;
    }
}
