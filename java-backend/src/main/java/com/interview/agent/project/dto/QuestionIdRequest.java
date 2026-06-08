package com.interview.agent.project.dto;

import jakarta.validation.constraints.NotNull;

/** body {question_id} —— attempt-start / attempts-history 复用；project-grilling 模块本地版。 */
public record QuestionIdRequest(
        @NotNull Long questionId
) {
}
