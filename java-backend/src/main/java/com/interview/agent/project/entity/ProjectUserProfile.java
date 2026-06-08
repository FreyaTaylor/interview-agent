package com.interview.agent.project.entity;

import java.time.Instant;

/**
 * 用户在某项目上的答题画像 —— 与 project_user_profile 表一一对应。
 *
 * <p>由 LLM 从历次拷打回答中异步抽取，给后续轮次出题/追问提供精准上下文。
 *
 * <p>字段说明：
 * <ul>
 *   <li>{@code projectFacts} — JSONB List&lt;String&gt;，扁平事实列表。每条是一段较完整的项目描述
 *       （如"订单状态机由 CREATED/PAID/SHIPPED 三态组成..."）。上限 50，超出保留最新。</li>
 *   <li>{@code version} — 乐观锁；抽取任务异步执行，并发写时 {@code WHERE version=?} 失败则重读重算。</li>
 * </ul>
 *
 * <p>对应表：V1 schema 第 7 张表，UNIQUE(project_id, user_id)。
 */
public record ProjectUserProfile(
        Long id,
        Long userId,
        Long projectId,
        Object projectFacts,       // JSONB → List<String>
        int version,
        Instant createdAt,
        Instant updatedAt
) {
}
