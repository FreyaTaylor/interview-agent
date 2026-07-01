package com.interview.agent.learn.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 删除单道题目请求体（POST /api/learn/question-delete）。
 * <p>带 {@code kpId} 是为了在 SQL WHERE 里再加一道防越权校验。
 */
public record QuestionDeleteRequest(
        @NotNull Long kpId,
        @NotNull Long questionId
) {
}
