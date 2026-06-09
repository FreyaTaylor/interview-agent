package com.interview.agent.interview.dto;

/** 保存草稿响应。 */
public record SaveDraftResponse(
        Long recordId,
        boolean isDraftOnly,
        boolean hasParsed
) {
}
