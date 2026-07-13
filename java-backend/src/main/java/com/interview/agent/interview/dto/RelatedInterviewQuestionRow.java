package com.interview.agent.interview.dto;

import java.time.Instant;

/**
 * findRelatedByKp 的平表投影行（questions 为 jsonb::text，供 service 解析成 List&lt;String&gt;）。
 */
public record RelatedInterviewQuestionRow(
        Long id,
        String questions,
        String tag,
        Float similarity,
        Long interviewRecordId,
        String company,
        String position,
        Instant createdAt
) {
}
