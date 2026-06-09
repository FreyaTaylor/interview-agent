package com.interview.agent.interview.mapper;

import java.time.Instant;

/**
 * 面试记录语义查重的最近邻命中行。
 *
 * <p>对应 {@code SELECT id, company, position, avg_score, created_at,
 * (embedding <=> :vec) AS distance} 的一行（{@code distance} 为 pgvector 余弦距离，
 * 0 完全相同、2 完全相反，余弦相似度 = 1 - distance）。
 *
 * <p>MyBatis 依赖 {@code map-underscore-to-camel-case} + 构造器形参名映射列。
 */
public record InterviewDuplicateMatch(
        Long id,
        String company,
        String position,
        Integer avgScore,
        Instant createdAt,
        double distance
) {
}
