package com.interview.agent.study.dto;

import jakarta.validation.constraints.NotNull;

/** body {attempt_id} — attempt-finish / attempt-detail 复用。 */
public record AttemptIdRequest(
        @NotNull Long attemptId
) {
}
