package com.interview.agent.project.entity;

import java.time.Instant;

/**
 * 项目树节点 — 与 project_node 表一一对应。
 *
 * <p>多模块共享：admin 模块（S6 CRUD）、project_grilling（S7 拷打）、interview（S8 匹配）。
 *
 * <p>与 KnowledgeNode 的差异（S6 doc §0 决策 4）：
 * <ul>
 *   <li>项目树<b>固定三层</b>（项目→话题→题目）；level≥3 即叶子（硬规则）</li>
 *   <li>无 interview_weight / mastery_level / study_count 等字段（项目题不走 study 流）</li>
 *   <li>仍写 user_id 列（一期固定 1）</li>
 * </ul>
 *
 * <p>embedding 在 Record 中不暴露，写入由 Mapper 用 ?::vector 字面量处理。
 */
public record ProjectNode(
        Long id,
        Long userId,              // 一期固定 1
        Long parentId,            // 根节点为 null
        String name,
        short level,              // 1=项目 / 2=话题 / 3=问题
        String nodeType,          // 'category' | 'leaf'
        int sortOrder,
        Instant createdAt,
        Instant updatedAt
) {
}
