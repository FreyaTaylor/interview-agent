package com.interview.agent.interview.entity;

import java.time.Instant;

/**
 * 面试真题 ↔ 知识点 关联行（interview_question_kp_link）。
 *
 * <p>真题属面试模块（interviewKnowledgeQuestionId 指向 interview_knowledge_question.id），
 * 知识点只被只读引用；knowledgePointName 是快照，知识点被删（kpId 置空）后仍保留当时的名。
 */
public record InterviewQuestionKpLink(
        Long id,
        Long userId,
        Long interviewKnowledgeQuestionId,
        Long knowledgePointId,
        String knowledgePointName,
        String source,
        Float similarity,
        Instant createdAt
) {
}
