package com.interview.agent.user.dto;

/**
 * 更新用户画像请求体。
 *
 * <p>JSON 字段 {@code profile_text}（Jackson 全局 SNAKE_CASE 自动绑定到 {@code profileText}）。
 */
public record ProfileUpdateRequest(String profileText) {
}
