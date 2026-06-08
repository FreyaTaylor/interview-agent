package com.interview.agent.project.dto;

import java.util.List;

/**
 * /attempt-start 响应。
 *
 * @param dialog       此时只含主问题
 * @param currentStep  已抛出的提问轮数（开题为 1）
 * @param maxSteps     最大轮数（主问 + 4 追问 = 5）
 */
public record AttemptStartResponse(
        Long attemptId,
        Long questionId,
        String questionContent,
        String topicName,
        List<Object> dialog,
        int currentStep,
        int maxSteps
) {
}
