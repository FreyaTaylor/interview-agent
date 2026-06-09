package com.interview.agent.interview.mapper;

/**
 * 语义查重懒回填投影：仅取尚未生成 embedding 的历史记录的 {@code id + raw_text}。
 *
 * <p>列名 snake_case（raw_text）经全局 map-underscore-to-camel-case 映射到 record 形参 rawText。
 */
public record InterviewEmbeddingBackfillRow(Long id, String rawText) {
}
