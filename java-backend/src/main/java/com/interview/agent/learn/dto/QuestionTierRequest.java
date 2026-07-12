package com.interview.agent.learn.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 切换单题 tier 请求体（POST /api/learn/question-tier）。
 * <p>{@code tier} ∈ core|ext；带 {@code kpId} 做防越权校验。
 */
public record QuestionTierRequest(
        @NotNull Long kpId,
        @NotNull Long questionId,
        @NotNull String tier
) {
}
