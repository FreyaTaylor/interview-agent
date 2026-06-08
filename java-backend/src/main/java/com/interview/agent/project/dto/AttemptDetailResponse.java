package com.interview.agent.project.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * /attempt-detail 响应（完整作答快照）。
 *
 * <p>未 finish 时 {@code finalScore} / {@code dimensions} / {@code rubricResult} /
 * {@code overallSummary} / {@code designIssues} / {@code extensionQa} / {@code finishedAt} 都可为 null。
 *
 * <p>v2 attempt 的 dimensions 已从 rubric_result 列拆出；v1 attempt 的历史 rubric list 仍走 rubricResult。
 */
public record AttemptDetailResponse(
        Long attemptId,
        Long questionId,
        String questionContent,
        String topicName,
        String status,
        List<Object> dialog,
        Integer finalScore,
        Map<String, Integer> dimensions,
        Object rubricResult,
        String overallSummary,
        Object designIssues,
        Object extensionQa,
        int followUpCount,
        int maxSteps,
        Instant finishedAt,
        Instant createdAt
) {
}
