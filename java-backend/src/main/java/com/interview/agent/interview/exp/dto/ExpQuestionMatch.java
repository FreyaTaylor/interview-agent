package com.interview.agent.interview.exp.dto;

/**
 * 面经问题域内语义召回结果 —— 问题节点 id + 与查询向量的余弦距离（越小越近）。
 */
public record ExpQuestionMatch(long id, double distance) {
}
