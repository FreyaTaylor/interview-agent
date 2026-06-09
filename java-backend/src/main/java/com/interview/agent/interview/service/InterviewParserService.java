package com.interview.agent.interview.service;

import com.interview.agent.interview.dto.PreviewParseResponse;

/** 面试文本解析服务：原始文本 -> turns/groups。 */
public interface InterviewParserService {

    /** 解析文本并输出结构化对话。 */
    PreviewParseResponse parse(String text);
}
