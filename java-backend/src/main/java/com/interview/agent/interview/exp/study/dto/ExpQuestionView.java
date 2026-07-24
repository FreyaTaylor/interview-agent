package com.interview.agent.interview.exp.study.dto;

import java.util.List;

/**
 * 「看看面经」单问题详情视图（讲解 + rubric + 推荐答案 + 看过次数）。
 *
 * @param bodyMd            讲解正文（Markdown；未生成为空）
 * @param contentStatus     pending | ready
 * @param rubric            采分点 [{key_point, hit_rule, score}]
 * @param recommendedAnswer 分点范例答案
 * @param viewCount         看过次数（木鱼敲击累计；未看过 0）
 * @param skipped           「不用看」标记（🚫 二值）
 * @param frequency         该问题出现频率
 * @param generated         本次是否新生成（true=刚调 LLM 生成；false=读库）
 */
public record ExpQuestionView(
        long questionId,
        String name,
        String domainName,
        String bodyMd,
        String contentStatus,
        List<Object> rubric,
        List<Object> recommendedAnswer,
        int viewCount,
        boolean skipped,
        int frequency,
        boolean generated
) {
}
