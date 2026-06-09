package com.interview.agent.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 认证相关配置（命名空间 {@code iagent.auth}）。
 *
 * <p>与 Python 端 {@code backend/services/user.py} 对齐：JWT 用 HS256 + 同一个 secret，
 * GitHub OAuth 走 read:user scope。所有敏感值从环境变量（.env）注入。
 *
 * <ul>
 *   <li>{@code jwtSecret}        —— JWT 签名密钥（HS256），必须与 Python 端相同才能互通 token</li>
 *   <li>{@code jwtExpireDays}    —— token 有效期天数，默认 30（与 Python JWT_EXPIRE_DAYS 一致）</li>
 *   <li>{@code githubClientId}   —— GitHub OAuth App client_id</li>
 *   <li>{@code githubClientSecret} —— GitHub OAuth App client_secret</li>
 *   <li>{@code frontendUrl}      —— 回调成功后携带 token 重定向回的前端地址</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "iagent.auth")
public record AuthProperties(
        String jwtSecret,
        Integer jwtExpireDays,
        String githubClientId,
        String githubClientSecret,
        String frontendUrl
) {
    public AuthProperties {
        if (jwtExpireDays == null) jwtExpireDays = 30;
        if (frontendUrl == null || frontendUrl.isBlank()) frontendUrl = "http://localhost:5173";
    }
}
