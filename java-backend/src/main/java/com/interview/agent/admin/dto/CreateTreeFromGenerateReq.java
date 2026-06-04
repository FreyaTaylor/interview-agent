package com.interview.agent.admin.dto;

/**
 * POST /api/admin/trees/from-generate 请求体。
 *
 * @param treeName     根节点名称，必填（如 "Redis"、"Spring Cloud"）
 * @param requirements 额外需求描述，可为空；空时回退用 treeName 当 requirements
 */
public record CreateTreeFromGenerateReq(String treeName, String requirements) {
}
