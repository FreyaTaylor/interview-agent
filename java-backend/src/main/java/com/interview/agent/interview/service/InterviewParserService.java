package com.interview.agent.interview.service;

import com.interview.agent.interview.dto.PreviewParseResponse;

/** 面试文本解析服务：原始文本 -> turns/groups。 */
public interface InterviewParserService {

    /** 解析文本并输出结构化对话（预分块走配置默认策略）。 */
    PreviewParseResponse parse(String text);

    /**
     * 解析文本，显式指定预分块策略（{@code fixed|semantic}）。
     * @param chunkStrategy 策略名；null/空 → 走配置默认。供 A/B 对比定长 vs 语义切分。
     */
    PreviewParseResponse parse(String text, String chunkStrategy);
}
