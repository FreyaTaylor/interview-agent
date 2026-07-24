package com.interview.agent.interview.exp.study.dto;

/**
 * 「看看面经」自评掌握度请求。
 *
 * @param questionId  问题节点 id
 * @param selfMastery 0-100（越界 clamp）；null 表示清除
 */
public record ExpSelfMasteryRequest(long questionId, Integer selfMastery) {
}
