package com.interview.agent.learn.entity;

import java.time.Instant;

/**
 * 知识点下的预生成面试题 — tree_node(node_type='question') + question_detail 侧表的组合视图。
 *
 * <p>骨架字段来自 tree_node：{@code id}、{@code content}=name（题干）、{@code subtopicId}=parent_id、
 * {@code sortOrder}、{@code userId}、{@code createdAt}；{@code knowledgePointId} 由所属子话题的父节点派生。
 *
 * <p>内容字段来自 question_detail：
 * <ul>
 *   <li>{@code tier} — 'core'(高频，默认答题范围) | 'ext'(扩展)</li>
 *   <li>{@code rubricTemplate} — 评分点数组 {@code [{key_point, score}, ...]}，总分 = 100（懒生成）</li>
 *   <li>{@code recommendedAnswer} — 范例答案（字符串数组或字符串，二者皆兼容前端）</li>
 * </ul>
 */
public record StudyQuestion(
        Long id,
        Long userId,
        Long knowledgePointId,
        Long subtopicId,
        String content,
        String tier,
        Object rubricTemplate,
        Object recommendedAnswer,
        int sortOrder,
        Instant createdAt
) {
}
