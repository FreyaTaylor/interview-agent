package com.interview.agent.interview.matcher;

/**
 * pgvector 最近邻查询的投影结果：节点 id / name / cosine distance。
 *
 * <p>对应 Python embedding_match_skill / project_node_matcher 里
 * {@code SELECT id, name, (embedding <=> :vec) AS distance} 的一行。
 * MyBatis 依赖 {@code map-underscore-to-camel-case} + 构造器形参名映射列。
 */
public record NodeMatch(
        Long id,
        String name,
        double distance
) {
}
