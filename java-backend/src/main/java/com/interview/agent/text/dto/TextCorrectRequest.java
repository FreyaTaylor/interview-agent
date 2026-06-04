package com.interview.agent.text.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * POST /api/text/correct 请求体。
 *
 * @param text    待纠正文本（来自前端 Web Speech API 的脏转写）
 * @param context 可选上下文（如当前题目原文），用于让 LLM 推断技术术语
 */
public record TextCorrectRequest(
        @NotBlank String text,
        String context
) {
}
