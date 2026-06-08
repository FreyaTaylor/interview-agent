package com.interview.agent.project.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * /attempts-history 响应。前端用列表渲染历次拷打记录。
 *
 * <p>{@code dialog} 在 history 列表里也下发，前端直接渲染对话气泡（与 Python 行为一致）。
 *
 * <p>v2 attempt 的 dimensions 已从 rubric_result 列拆出；v1 attempt 的历史 rubric list 仍走 rubricResult。
 */
public record AttemptsHistoryResponse(
        Long questionId,
        List<Item> attempts
) {
    public record Item(
            Long attemptId,
            String status,
            Integer finalScore,
            int followUpCount,
            List<Object> dialog,
            Map<String, Integer> dimensions,
            Object rubricResult,
            String overallSummary,
            Object designIssues,
            Object extensionQa,
            Instant finishedAt,
            Instant createdAt
    ) {
    }
}
