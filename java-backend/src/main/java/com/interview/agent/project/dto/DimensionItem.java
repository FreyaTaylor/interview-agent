package com.interview.agent.project.dto;

/**
 * /dimensions-list 单项响应（一个 L2 话题）。
 *
 * @param questionCount  该话题下 L3 叶子题目总数
 * @param attemptCount   该话题下所有 L3 已 finished 的总作答次数
 * @param avgScore       话题分（0-100，全部题未答返 null）
 */
public record DimensionItem(
        Long id,
        String name,
        int questionCount,
        int attemptCount,
        Integer avgScore
) {
}
