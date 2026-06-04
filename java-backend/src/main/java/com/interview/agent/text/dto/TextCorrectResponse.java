package com.interview.agent.text.dto;

/** /api/text/correct 响应：corrected 为纠正后文本；LLM 挂了时返回原文（前端无感）。 */
public record TextCorrectResponse(String corrected) {
}
