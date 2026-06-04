package com.interview.agent.study.dto;

import java.util.List;

/**
 * POST /api/study/attempt-turn 响应。
 *
 * <p>{@code turnRubric.hits} = {@code List<Map<String,Object>>}（key_point/hit/reason），透传 LLM 输出。
 * {@code followUpQuestion} 为 null 时前端应自动调 finish。
 */
public record AttemptTurnResponse(
        Long attemptId,
        List<Object> dialog,
        TurnRubric turnRubric,
        String followUpType,
        String followUpQuestion,
        boolean canFinish,
        int currentStep,
        int maxSteps
) {
    /**
     * 单轮评估摘要。
     *
     * @param covered rubric 关键点是否基本全覆盖
     * @param mastery 最后一次回答的掌握度（high / mid / low）
     */
    public record TurnRubric(
            String feedback,
            List<Object> hits,
            boolean covered,
            String mastery
    ) {
    }
}
