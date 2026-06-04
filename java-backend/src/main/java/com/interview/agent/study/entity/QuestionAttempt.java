package com.interview.agent.study.entity;

import java.time.Instant;

/**
 * 一次完整作答（多态：study | project）—— 与 question_attempt 表一一对应。
 *
 * <p>JSONB 字段类型为 {@code Object}（受 {@code JsonbTypeHandler} 约束），实际内容：
 * <ul>
 *   <li>{@code dialog} — {@code List<Map<String,Object>>}，累积每轮 question / answer / feedback / follow_up</li>
 *   <li>{@code rubricResult} — {@code Map<String,Object>}，含 hits[] / missed_key_points / (project 用 design_issues)</li>
 *   <li>{@code designIssues} — 项目题专用，study 留 null</li>
 *   <li>{@code extensionQa} — 项目题专用，study 留 null</li>
 * </ul>
 *
 * <p>{@code status} ∈ {in_progress, finished, abandoned}；本期只走 in_progress → finished。
 */
public record QuestionAttempt(
        Long id,
        Long userId,
        String questionType,
        Long questionId,
        String status,
        Short finalScore,
        Object rubricResult,
        String overallSummary,
        Object designIssues,
        Object extensionQa,
        Object dialog,
        short followUpCount,
        Instant finishedAt,
        Instant createdAt
) {
}
