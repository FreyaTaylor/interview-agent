package com.interview.agent.learn.entity;

import java.time.Instant;

/**
 * 知识点下的预生成面试题 — 与 study_question 表一一对应。
 *
 * <p>JSONB 字段：
 * <ul>
 *   <li>{@code rubricTemplate} — 评分点数组 {@code [{key_point, score}, ...]}，总分 = 100</li>
 *   <li>{@code recommendedAnswer} — 范例答案（字符串数组或字符串，二者皆兼容前端）</li>
 * </ul>
 *
 * <p>由 S4 Learn 模块（懒生成）写入；S3 Study 模块只读。
 *
 * <p>{@code subtopicId}：所属子话题（目标题归属）。历史题可能为 null；新流程必填。
 */
public record StudyQuestion(
        Long id,
        Long userId,
        Long knowledgePointId,
        Long subtopicId,
        String content,
        Object rubricTemplate,
        Object recommendedAnswer,
        int sortOrder,
        Instant createdAt
) {
}
