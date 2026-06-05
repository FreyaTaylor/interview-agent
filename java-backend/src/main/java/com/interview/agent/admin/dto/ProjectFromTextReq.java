package com.interview.agent.admin.dto;

/**
 * S6 项目从文本解析请求体。
 *
 * @param text 用户粘贴的项目描述（简历段落 / 语音转写 / 自由口述）
 */
public record ProjectFromTextReq(String text) {
}
