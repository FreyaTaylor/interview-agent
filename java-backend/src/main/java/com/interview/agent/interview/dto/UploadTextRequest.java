package com.interview.agent.interview.dto;

import jakarta.validation.constraints.NotBlank;

/** 文本上传请求。 */
public record UploadTextRequest(
        @NotBlank String text,
        String company,
        String position
) {
}
