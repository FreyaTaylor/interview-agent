package com.interview.agent.learn.entity;

import java.time.Instant;

/**
 * 知识子话题 — 与 knowledge_subtopic 表一一对应。
 *
 * <p>{@code followups} 为 JSONB 列，由 {@link com.interview.agent.infra.db.JsonbTypeHandler}
 * 解析为 {@code Object}（实际是 {@code List<Map<String,Object>>}，每项形如
 * {@code { "q": "...", "a": "...", "created_at": "ISO-8601" }}）。
 * 这里用 Object 保留与 StudyQuestion 同样的 typeHandler 适配宽容性。
 *
 * <p>{@code source}：{@code "initial"} = 初次讲解生成；{@code "chat"} = 探索对话期间新增。
 */
public record KnowledgeSubtopic(
        Long id,
        Long kpId,
        String title,
        String bodyMd,
        Short importance,
        Object followups,
        Integer sortOrder,
        String source,
        Long userId,
        Instant createdAt
) {
}
