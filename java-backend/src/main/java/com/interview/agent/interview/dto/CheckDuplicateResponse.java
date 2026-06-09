package com.interview.agent.interview.dto;

/** 重复检测响应。 */
public record CheckDuplicateResponse(
        boolean duplicate,
        Long recordId,
        String company,
        String position,
        String createdAt,
        Integer avgScore
) {
    public static CheckDuplicateResponse notDuplicate() {
        return new CheckDuplicateResponse(false, null, null, null, null, null);
    }
}
