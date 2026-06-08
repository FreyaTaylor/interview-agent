package com.interview.agent.project.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** POST /api/project-grilling/attempt-turn 请求。 */
public record AttemptTurnRequest(
        @NotNull Long attemptId,
        @NotBlank String userAnswer
) {
}
