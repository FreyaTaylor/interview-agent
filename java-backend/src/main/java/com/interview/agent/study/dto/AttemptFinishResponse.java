package com.interview.agent.study.dto;

/**
 * POST /api/study/attempt-finish 响应。
 *
 * <p>{@code rubricResult} 透传 LLM JSON（含 hits[] + missed_key_points）。
 * {@code masteryLevel} 是该 attempt 收尾后该 KP 的最新掌握度（已写回 knowledge_node）。
 */
public record AttemptFinishResponse(
        Long attemptId,
        String status,
        int finalScore,
        Object rubricResult,
        String overallSummary,
        Long kpId,
        Integer masteryLevel
) {
}
