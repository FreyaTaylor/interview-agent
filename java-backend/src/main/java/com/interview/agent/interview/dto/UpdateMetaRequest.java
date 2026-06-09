package com.interview.agent.interview.dto;

import jakarta.validation.constraints.NotNull;

/** 更新公司/岗位请求。 */
public record UpdateMetaRequest(
        @NotNull Long recordId,
        String company,
        String position
) {
}
