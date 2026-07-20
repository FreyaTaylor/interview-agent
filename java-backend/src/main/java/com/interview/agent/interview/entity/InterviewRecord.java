package com.interview.agent.interview.entity;

import java.time.Instant;

/**
 * 面试复盘主记录 —— 对应 interview_record 表。
 *
 * <p>parsedQuestions / clusterResult / draftTurns / draftGroups 都是 JSONB，
 * 通过 JsonbTypeHandler 反序列化到 Object（Map/List）。
 */
public record InterviewRecord(
        Long id,
        String rawText,
        String company,
        String position,
        String textHash,
        Integer avgScore,
        String passEstimate,
        Object parsedQuestions,
        Object clusterResult,
        String summaryReport,
        Object draftTurns,
        Object draftGroups,
        Instant createdAt,
        String reviewStatus
) {
}
