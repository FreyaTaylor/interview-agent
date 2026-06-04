package com.interview.agent.admin.dto;

/**
 * POST /api/admin/trees/from-text 请求体。
 *
 * @param text 用户粘贴的 Markdown / 纯文本（缩进或编号体现层级）
 */
public record CreateTreeFromTextReq(String text) {
}
