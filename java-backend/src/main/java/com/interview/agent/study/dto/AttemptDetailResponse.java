package com.interview.agent.study.dto;

import java.time.Instant;
import java.util.List;

/**
 * POST /api/study/attempt-detail 响应；完整 attempt 字段 + 题目原文。
 *
 * <p>用于刷新页面 / 历史回看；{@code rubricResult} 在 status=in_progress 时为 null。
 */
public record AttemptDetailResponse(
        Long attemptId,
        Long questionId,
        String questionContent,
        String status,
        List<Object> dialog,
        Integer finalScore,
        Object rubricResult,
        String overallSummary,
        int followUpCount,
        int maxSteps,
        Instant finishedAt,
        Instant createdAt
) {
}
