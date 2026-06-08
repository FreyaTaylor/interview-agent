package com.interview.agent.project.dto;

import java.util.List;
import java.util.Map;

/**
 * /attempt-finish 响应（v2 多维度评分）。
 *
 * <p>v1 的 rubricResult 字段移除（项目场景无 rubric）；新增 dimensions 4 维分数。
 * v2 attempt 的 final_score 由后端按 dimensions 加权计算（权重 0.3/0.3/0.25/0.15）。
 *
 * @param dimensions     {fact_clarity, design_quality, depth, communication} 各 0-10；v1 attempt 历史返 null
 * @param rubricResult   v1 兼容字段：v1 attempt 返历史 list，v2 attempt 返 null
 * @param designIssues   设计缺陷列表（v2 由 LLM 基于整段 dialog 重新提炼）
 * @param extensionQa    3 个延伸 Q&amp;A
 */
public record AttemptFinishResponse(
        Long attemptId,
        String status,
        int finalScore,
        Map<String, Integer> dimensions,
        Object rubricResult,
        String overallSummary,
        Object designIssues,
        Object extensionQa,
        List<Object> dialog
) {
}
