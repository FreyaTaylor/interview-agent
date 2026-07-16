package com.interview.agent.interview.dto;

import jakarta.validation.constraints.NotBlank;

/** 文本上传请求。 */
public record UploadTextRequest(
        @NotBlank String text,
        String company,
        String position,
        /** 可选预分块策略（fixed|semantic）—— 仅前解析 A/B 对比用，缺省走配置默认。 */
        String chunkStrategy
) {
}
