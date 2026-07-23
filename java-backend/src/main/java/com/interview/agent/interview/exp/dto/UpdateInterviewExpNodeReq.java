package com.interview.agent.interview.exp.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 修改面经节点请求。
 *
 * <p>{@code id} 必传；其余字段 {@code null} 表示"不变"。
 * 仅当 {@code movingParent=true} 时才视为跨父移动（前端控制），否则忽略 {@code parentId}
 * ——与知识树 {@code UpdateKnowledgeNodeReq} 同约定。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UpdateInterviewExpNodeReq(
        long id,
        String name,
        Long parentId,
        Integer sortOrder,
        Boolean movingParent
) {
    public boolean isMovingParent() {
        return Boolean.TRUE.equals(movingParent);
    }
}
