package com.interview.agent.admin.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 修改项目节点请求（S6）。
 *
 * <p>{@code id} 必传（走 body，不走 PathVariable）。其余字段均可空，null = 不变。
 * <p>parentId 与 S1 同样的二义性问题（"不传"vs"传 null"）→ 仅当 movingParent=true 时才动 parent。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UpdateProjectNodeReq(
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
