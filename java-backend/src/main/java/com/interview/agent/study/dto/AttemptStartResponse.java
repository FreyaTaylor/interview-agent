package com.interview.agent.study.dto;

import java.util.List;

/** POST /api/study/attempt-start 响应。 */
public record AttemptStartResponse(
        Long attemptId,
        Long questionId,
        String question,
        List<Object> dialog,
        int currentStep,
        int maxSteps
) {
}
