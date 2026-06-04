package com.interview.agent.study.dto;

import jakarta.validation.constraints.NotNull;

/** body {question_id} — attempt-start / attempts-history 复用。 */
public record QuestionIdRequest(
        @NotNull Long questionId
) {
}
