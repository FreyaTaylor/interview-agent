package com.interview.agent.interview.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.Map;

/** finalize 请求：前端校对后的 turns + groups。 */
public record FinalizeRequest(
        @NotEmpty List<Map<String, Object>> turns,
        @NotEmpty List<Map<String, Object>> groups,
        String company,
        String position
) {
}
