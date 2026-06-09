package com.interview.agent.interview.dto;

/** 历史列表单项。 */
public record InterviewHistoryItem(
        Long id,
        String company,
        String position,
        Integer avgScore,
        String passEstimate,
        String createdAt,
        boolean hasParsed,
        boolean hasDraft
) {
}
