package com.interview.agent.knowledge.dto;

/**
 * 全量知识树（管理视图）的平表投影行 —— findFullTree 的结果单元。
 *
 * 不复用 KnowledgeNode 实体：子话题/问题节点的 interview_weight/mastery_level 为 NULL，
 * 这里用 COALESCE 归零并额外 LEFT JOIN question_detail 带出 tier/source，供前端加徽标。
 */
public record KnowledgeFullRow(
        long id,
        Long parentId,
        String name,
        short level,
        String nodeType,
        short interviewWeight,
        int sortOrder,
        int masteryLevel,
        int selfMastery,
        String tier,
        String source
) {
}
