package com.interview.agent.project.dto;

import java.time.Instant;
import java.util.List;

/**
 * 「项目 → 相关面试真题」只读视图（GET /api/interview/related-project-questions）。
 *
 * <p>真题属面试模块，项目只读引用；通过 interview_project_question.project_node_id 落在项目子树内建立关联。
 */
public record RelatedProjectQuestion(
        Long id,
        List<String> questions,
        String projectName,
        Long interviewRecordId,
        String company,
        String position,
        Instant createdAt
) {
}
