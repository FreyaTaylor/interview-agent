package com.interview.agent.interview.dto;

import jakarta.validation.constraints.NotBlank;

/** 重复检测请求。 */
public record CheckDuplicateRequest(
        @NotBlank String textHash
) {
}
