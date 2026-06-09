package com.interview.agent.interview.dto;

/** 更新公司/岗位请求体（recordId 走路径参数，对齐 Python: PATCH /history/{id}）。 */
public record UpdateMetaBody(
        String company,
        String position
) {
}
