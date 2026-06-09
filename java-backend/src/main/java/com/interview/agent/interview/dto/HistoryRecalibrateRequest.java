package com.interview.agent.interview.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.Map;

/** 继续校准请求。 */
public record HistoryRecalibrateRequest(
        @NotNull Long recordId,
        @NotEmpty List<Map<String, Object>> turns,
        @NotEmpty List<Map<String, Object>> groups
) {
}
