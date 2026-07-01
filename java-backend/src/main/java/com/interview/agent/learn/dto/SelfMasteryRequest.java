package com.interview.agent.learn.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 设置/清除知识点自评掌握度请求体（POST /api/learn/self-mastery）。
 * <p>{@code selfMastery} 可为 null：表示清除自评（恢复未自评态）。
 * <p>非 null 时取值 0-100（前端三档 40/75/100），后端 clamp 到 [0,100]。
 */
public record SelfMasteryRequest(
        @NotNull Long kpId,
        Integer selfMastery
) {
}
