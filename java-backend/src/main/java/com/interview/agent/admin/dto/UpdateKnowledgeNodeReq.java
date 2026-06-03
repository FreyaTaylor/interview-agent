package com.interview.agent.admin.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 修改知识树节点请求 — 所有字段均可空，null 表示"不变"。
 *
 * 注意：parentId == null 在 Java 端无法区分"不传 / 传 null"。
 * 这里约定：仅当 movingParent=true 时才视为跨父移动（前端控制）；
 *          否则忽略 parentId。
 * 与 Python 端"model_fields_set 包含 parent_id"的语义对齐。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UpdateKnowledgeNodeReq(
        String name,
        Short interviewWeight,
        Long parentId,
        Integer sortOrder,
        Boolean movingParent
) {
    public boolean isMovingParent() {
        return Boolean.TRUE.equals(movingParent);
    }
}
