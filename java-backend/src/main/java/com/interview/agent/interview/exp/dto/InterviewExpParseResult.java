package com.interview.agent.interview.exp.dto;

/**
 * 面经解析结果摘要（供管理页提示）。
 *
 * @param duplicateSource   整篇面经被来源去重拦截（hash 或整篇 embedding 命中）
 * @param message           人类可读提示
 * @param sourceId          新落库的来源 id（duplicateSource=true 时为 0）
 * @param totalParsed       LLM 抽出的问题总数
 * @param newQuestions      新建的问题节点数
 * @param matchedQuestions  命中已有问题（计频 +1）数
 * @param newDomains        新建的知识域数
 */
public record InterviewExpParseResult(
        boolean duplicateSource,
        String message,
        long sourceId,
        int totalParsed,
        int newQuestions,
        int matchedQuestions,
        int newDomains
) {
}
