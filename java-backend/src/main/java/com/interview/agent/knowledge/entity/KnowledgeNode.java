package com.interview.agent.knowledge.entity;

import java.time.Instant;

/**
 * 知识树节点 — 与 knowledge_node 表一一对应。
 * 多个模块共享：admin 模块 CRUD、knowledge 模块查询、study 模块掌握度派生。
 * embedding 字段在本 Record 中不暴露（应用层很少需要原始向量），
 * 写入时由 Repository 自行处理 '?::vector' 字面量。
 */
public record KnowledgeNode(
        Long id,
        Long parentId,            // 根节点为 null
        String name,
        short level,              // 层级（1=根，向下递增，不限层数；nodeType 与 level 解耦）
        String nodeType,          // 'category' | 'leaf'
        short interviewWeight,    // 默认 3
        int sortOrder,
        boolean isUserCreated,
        Short masteryLevel,       // S3 study 派生：可空（从未学过）
        int studyCount,           // S3 study 派生：finished 次数累加
        Short selfMastery,        // 用户自评掌握度 0-100：可空（未自评），与 masteryLevel 独立
        Instant createdAt,
        Instant updatedAt
) {
}
