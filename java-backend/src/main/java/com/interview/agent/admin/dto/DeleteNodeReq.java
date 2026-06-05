package com.interview.agent.admin.dto;

/**
 * 单 id 删除请求 —— 知识树 / 项目树 admin 共用。
 *
 * <p>独立于 entity，符合 java-style §3.3"全 POST + body 传参，禁止 PathVariable"约束。
 */
public record DeleteNodeReq(long id) {
}
