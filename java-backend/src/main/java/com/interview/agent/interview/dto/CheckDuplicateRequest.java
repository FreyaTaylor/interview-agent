package com.interview.agent.interview.dto;

import jakarta.validation.constraints.NotBlank;

/** 重复检测请求：传整段面试文本，后端做语义查重。 */
public record CheckDuplicateRequest(
        @NotBlank String text
) {
}
