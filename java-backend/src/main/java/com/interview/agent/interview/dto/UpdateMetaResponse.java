package com.interview.agent.interview.dto;

/** 更新公司/复盘状态响应。 */
public record UpdateMetaResponse(
        Long id,
        String company,
        String reviewStatus
) {
}
