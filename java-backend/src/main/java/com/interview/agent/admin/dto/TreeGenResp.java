package com.interview.agent.admin.dto;

/**
 * 树生成统一响应：返回根节点 id / 根名 / 叶子数。
 */
public record TreeGenResp(long rootId, String name, int leafCount) {
}
