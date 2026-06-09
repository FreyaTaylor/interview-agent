package com.interview.agent.interview.entity;

import java.time.Instant;

/** interview_knowledge_question 表实体。 */
public record InterviewKnowledgeQuestion(
        Long id,
        Long interviewRecordId,
        Long knowledgeNodeId,
        String tag,
        Object questions,
        String userAnswer,
        String originalDialogue,
        Object scoreResult,
        Instant createdAt
) {
}
