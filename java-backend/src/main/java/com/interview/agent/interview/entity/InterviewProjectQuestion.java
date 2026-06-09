package com.interview.agent.interview.entity;

import java.time.Instant;

/** interview_project_question 表实体。 */
public record InterviewProjectQuestion(
        Long id,
        Long interviewRecordId,
        Long projectNodeId,
        String projectName,
        Object questions,
        String userAnswer,
        String originalDialogue,
        Object scoreResult,
        Instant createdAt
) {
}
