package com.interview.agent.interview.matcher;

/**
 * pgvector 最近邻查询的投影结果：节点 id / name / 父路径 / cosine distance。
 *
 * <p>对应 Python embedding_match_skill / project_node_matcher 里
 * {@code SELECT id, name, (embedding <=> :vec) AS distance} 的一行。
 * MyBatis 依赖 {@code map-underscore-to-camel-case} + 构造器形参名映射列。
 *
 * <p>{@code path}：候选叶子的父节点名（如 {@code Redis}），供 rerank 时给 LLM 展示"域"
 * 上下文，避免跨技术域错配（如"Spring 事务"错配到"Redis 事务"）。项目匹配用不到，查询里置 NULL。
 */
public record NodeMatch(
        Long id,
        String name,
        String path,
        double distance
) {
}
