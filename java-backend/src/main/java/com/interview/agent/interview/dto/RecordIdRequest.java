package com.interview.agent.interview.dto;

import jakarta.validation.constraints.NotNull;

/** 通用 record_id 请求。 */
public record RecordIdRequest(
        @NotNull Long recordId
) {
}
