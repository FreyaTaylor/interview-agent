package com.interview.agent.interview.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.Map;

/** 保存草稿请求。 */
public record SaveDraftRequest(
        Long recordId,
        @NotEmpty List<Map<String, Object>> turns,
        @NotEmpty List<Map<String, Object>> groups,
        String company,
        String position
) {
}
