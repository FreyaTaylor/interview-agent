package com.interview.agent.project.entity;

import java.time.Instant;

/**
 * 项目元数据 — 与 project 表一一对应。
 *
 * <p>用户视角的"项目"，与 project_node 树根一对一关联（root_node_id）。
 * S6 创建：from-text 解析后同步插入此表（name=root.name, description=raw_text, root_node_id=root.id）；
 * 删除：当 root_node_id 被删除时由 DB ON DELETE SET NULL 自动置空（V1 schema 声明）。
 *
 * <p>tech_stack 是 JSONB；一期 from-text 不填，留作 P1 编辑接口。
 */
public record Project(
        Long id,
        Long userId,
        String name,
        String description,
        String techStack,         // JSONB 原文（一期未用，String 兜底）
        String role,
        String highlights,
        Long rootNodeId,
        Instant createdAt
) {
}
