package com.interview.agent.interview.dto;

import java.time.Instant;
import java.util.List;

/**
 * 「知识点 → 相关面试真题」只读视图（GET /api/interview/related-questions）。
 *
 * <p>真题属面试模块，这里只被知识点/项目只读引用；similarity 为语义召回相似度。
 */
public record RelatedInterviewQuestion(
        Long id,
        List<String> questions,
        String tag,
        Float similarity,
        Long interviewRecordId,
        String company,
        String position,
        Instant createdAt
) {
}
