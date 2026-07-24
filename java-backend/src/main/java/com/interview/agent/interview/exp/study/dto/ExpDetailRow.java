package com.interview.agent.interview.exp.study.dto;

/**
 * 「看看面经」问题详情行（Mapper 读出的原始行；rubric/answer 以 JSON 文本返回，Service 再解析）。
 */
public record ExpDetailRow(
        long questionId,
        String name,
        String domainName,
        int viewCount,
        int frequency,
        String bodyMd,
        String contentStatus,
        String rubricTemplate,
        String recommendedAnswer
) {
}
