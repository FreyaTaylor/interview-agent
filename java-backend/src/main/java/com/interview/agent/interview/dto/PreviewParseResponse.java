package com.interview.agent.interview.dto;

import java.util.List;
import java.util.Map;

/** 预解析响应。 */
public record PreviewParseResponse(
        List<Map<String, Object>> turns,
        List<Map<String, Object>> groups,
        String summary
) {
}
