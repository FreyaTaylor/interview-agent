package com.interview.agent.learn.dto;

/**
 * POST /api/learn/content 响应（仅讲解，不含题目；题目走 /api/learn/questions）。
 * mastery_level 暂返回 0（S3 完成后由 QaAggregate 注入真值）。
 */
public record ContentView(
        Long knowledgePointId,
        String knowledgePointName,
        String content,
        int masteryLevel,
        String lastStudiedAt,
        boolean generated
) {
}
