package com.interview.agent.learn.dto;

import java.util.List;

/**
 * POST /api/learn/content 响应。
 *
 * <p>S4 重构后讲解以子话题列表呈现，{@code subtopics} 至少 1 条；首次生成 {@code generated=true}。
 * <p>{@code masteryLevel} 直读 {@code knowledge_node.mastery_level}（S3 study/finish 钩子实时写入），
 * 从未学过 → 0。{@code lastStudiedAt} 该列未持久化（V12 未建），统一返 null。
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
