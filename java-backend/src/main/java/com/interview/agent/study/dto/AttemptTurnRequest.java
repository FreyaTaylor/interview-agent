package com.interview.agent.study.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** POST /api/study/attempt-turn 请求。 */
public record AttemptTurnRequest(
        @NotNull Long attemptId,
        @NotBlank String userAnswer
) {
}
