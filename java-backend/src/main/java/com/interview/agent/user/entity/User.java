package com.interview.agent.user.entity;

import java.time.LocalDateTime;

/**
 * user 表实体（GitHub OAuth）。
 *
 * <p>对应 V1 schema 第 1 张表 {@code "user"}（user 是 PG 保留字，SQL 里须加双引号）。
 * 字段与 Python {@code backend/models/user.py} 对齐。
 */
public record User(
        Long id,
        String username,
        String role,
        String profileText,
        Long githubId,
        String githubLogin,
        String avatarUrl,
        LocalDateTime createdAt
) {
}
