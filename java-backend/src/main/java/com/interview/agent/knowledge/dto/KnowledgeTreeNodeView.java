package com.interview.agent.knowledge.dto;

import java.util.List;

/**
 * 知识树节点视图 —— GET /api/knowledge/tree 的嵌套响应单元。
 *
 * 字段与 Python 端 backend.services.knowledge_node.build_knowledge_tree 对齐：
 *   id / parent_id / name / level / node_type / interview_weight / sort_order
 *   mastery_level / study_count / children
 *
 * masteryLevel / studyCount 是叶子节点的掌握度派生值，依赖 Study 模块（S3）。
 * S2 阶段先恒定 0，待 S3 完成后由 QaAggregateService 注入真值。
 */
public record KnowledgeTreeNodeView(
        long id,
        Long parentId,
        String name,
        short level,
        String nodeType,
        short interviewWeight,
        int sortOrder,
        int masteryLevel,
        int studyCount,
        int selfMastery,
        List<KnowledgeTreeNodeView> children,
        String tier,
        String source,
        boolean hasInterviewQuestions
) {
}
