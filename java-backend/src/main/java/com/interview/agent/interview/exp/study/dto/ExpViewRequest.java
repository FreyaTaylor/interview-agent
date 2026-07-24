package com.interview.agent.interview.exp.study.dto;

/**
 * 「看看面经」看过次数 +1 请求（木鱼敲一下）。
 *
 * @param questionId 问题节点 id
 */
public record ExpViewRequest(long questionId) {
}
