package com.interview.agent.interview.dto;

import java.util.List;
import java.util.Map;

/** finalize 响应。 */
public record FinalizeResponse(
        Long recordId,
        List<Map<String, Object>> turns,
        List<Map<String, Object>> groups,
        Map<String, Integer> stats,
        int avgScore,
        String passEstimate,
        Map<String, Object> overallAnalysis
) {
}
