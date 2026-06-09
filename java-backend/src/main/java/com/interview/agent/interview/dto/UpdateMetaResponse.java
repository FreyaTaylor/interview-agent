package com.interview.agent.interview.dto;

/** 更新公司/岗位响应。 */
public record UpdateMetaResponse(
        Long id,
        String company,
        String position
) {
}
