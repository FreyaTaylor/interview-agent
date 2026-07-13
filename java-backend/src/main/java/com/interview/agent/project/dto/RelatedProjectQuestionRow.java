package com.interview.agent.project.dto;

import java.time.Instant;

/**
 * findRelatedByProject 的平表投影行（questions 为 jsonb::text，供 service 解析成 List&lt;String&gt;）。
 */
public record RelatedProjectQuestionRow(
        Long id,
        String questions,
        String projectName,
        Long interviewRecordId,
        String company,
        String position,
        Instant createdAt
) {
}
