package com.interview.agent.learn.entity;

import java.time.Instant;

/**
 * 知识点讲解长文 — 与 knowledge_content 表一一对应（每 kp 唯一）。
 * user_additions 一期未启用，留空字段以便后续按需扩展。
 */
public record KnowledgeContent(
        Long id,
        Long knowledgePointId,
        Long userId,
        String content,
        Object userAdditions,
        Instant createdAt,
        Instant updatedAt
) {
}
