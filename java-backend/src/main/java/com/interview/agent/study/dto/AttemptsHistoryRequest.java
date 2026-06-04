package com.interview.agent.study.dto;

import jakarta.validation.constraints.NotNull;

/** POST /api/study/attempts-history 请求。 */
public record AttemptsHistoryRequest(
        @NotNull Long questionId,
        Integer limit
) {
}
