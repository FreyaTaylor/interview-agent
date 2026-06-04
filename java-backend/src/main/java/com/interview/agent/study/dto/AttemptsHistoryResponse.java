package com.interview.agent.study.dto;

import java.time.Instant;
import java.util.List;

/** POST /api/study/attempts-history 响应。 */
public record AttemptsHistoryResponse(
        Long questionId,
        List<Item> attempts
) {
    public record Item(
            Long attemptId,
            String status,
            Integer finalScore,
            int followUpCount,
            Instant finishedAt,
            Instant createdAt
    ) {
    }
}
