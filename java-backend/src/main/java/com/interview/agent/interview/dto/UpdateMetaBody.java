package com.interview.agent.interview.dto;

/** 更新公司/复盘状态请求体（recordId 走路径参数）。reviewStatus：pending/reviewed。 */
public record UpdateMetaBody(
        String company,
        String reviewStatus
) {
}
