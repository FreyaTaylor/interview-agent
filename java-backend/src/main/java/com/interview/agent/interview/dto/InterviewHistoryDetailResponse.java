package com.interview.agent.interview.dto;

import java.util.List;
import java.util.Map;

/** 历史详情响应。 */
public record InterviewHistoryDetailResponse(
        Long recordId,
        String company,
        String position,
        String rawText,
        List<Map<String, Object>> groups,
        List<Map<String, Object>> turns,
        String summary,
        Map<String, Integer> stats,
        int avgScore,
        String passEstimate,
        Map<String, Object> overallAnalysis,
        boolean parseError,
        String createdAt,
        boolean hasDraft,
        boolean hasParsed,
        Object draftTurns,
        Object draftGroups
) {
}
