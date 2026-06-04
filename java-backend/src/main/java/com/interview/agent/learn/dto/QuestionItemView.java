package com.interview.agent.learn.dto;

/**
 * 学习页"高频面试题"列表项（也是 regenerate-questions 的响应元素）。
 * recommendedAnswer 可能是 {@code List<String>} 或 {@code String}，Jackson 透传。
 *
 * <p>{@code questionScore} 由 Study 模块的 ScoreAggregateService 计算（最近 N 次 finished 平均），
 * 无作答记录时为 {@code null}；答题页用作分数 badge。
 */
public record QuestionItemView(
        Long id,
        String question,
        int sortOrder,
        Object recommendedAnswer,
        Integer questionScore
) {
}
