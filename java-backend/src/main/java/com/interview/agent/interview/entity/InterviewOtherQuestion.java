package com.interview.agent.interview.entity;

import java.time.Instant;

/** interview_other_question 表实体。 */
public record InterviewOtherQuestion(
        Long id,
        Long interviewRecordId,
        String content,
        String tag,
        String userAnswer,
        Object extra,
        Instant createdAt
) {
}
