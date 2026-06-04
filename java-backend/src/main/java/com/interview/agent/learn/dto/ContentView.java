package com.interview.agent.learn.dto;

import java.util.List;

/**
 * POST /api/learn/content 响应。
 *
 * <p>S4 重构后讲解以子话题列表呈现，{@code subtopics} 至少 1 条；首次生成 {@code generated=true}。
 * mastery_level / last_studied_at 暂返回 0/null（S3 完成后由 QaAggregate 注入真值）。
 */
public record ContentView(
        Long knowledgePointId,
        String knowledgePointName,
        List<SubtopicView> subtopics,
        int masteryLevel,
        String lastStudiedAt,
        boolean generated
) {
}
