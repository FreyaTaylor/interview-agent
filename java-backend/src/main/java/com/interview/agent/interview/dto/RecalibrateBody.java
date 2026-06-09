package com.interview.agent.interview.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.Map;

/** 继续校准请求体（recordId 走路径参数，对齐 Python: POST /history/{id}/recalibrate）。 */
public record RecalibrateBody(
        @NotEmpty List<Map<String, Object>> turns,
        @NotEmpty List<Map<String, Object>> groups
) {
}
