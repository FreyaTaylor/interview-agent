package com.interview.agent.learn.dto;

/**
 * 学习页"高频面试题"列表项（也是 regenerate-questions 的响应元素）。
 * recommendedAnswer 可能是 {@code List<String>} 或 {@code String}，Jackson 透传。
 */
public record QuestionItemView(
        Long id,
        String question,
        int sortOrder,
        Object recommendedAnswer
) {
}
