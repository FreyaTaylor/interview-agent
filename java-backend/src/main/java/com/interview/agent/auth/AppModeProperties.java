package com.interview.agent.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 部署模式配置（命名空间 {@code iagent}）。
 *
 * <p>用于区分开源自用版与运营部署版，避免 self-hosted 用户被 GitHub OAuth / 邀请码卡住。
 */
@ConfigurationProperties(prefix = "iagent")
public record AppModeProperties(
        String deployMode,
        String authMode,
        Boolean inviteRequired
) {
    public AppModeProperties {
        if (deployMode == null || deployMode.isBlank()) deployMode = "self_hosted";
        if (authMode == null || authMode.isBlank()) authMode = "single_user";
        if (inviteRequired == null) inviteRequired = false;
    }

    /** 是否为开源自托管模式。 */
    public boolean selfHosted() {
        return "self_hosted".equalsIgnoreCase(deployMode);
    }

    /** 是否固定使用本地单用户。 */
    public boolean singleUser() {
        return "single_user".equalsIgnoreCase(authMode);
    }

    /** Hosted + GitHub 模式下是否要求新用户邀请码。 */
    public boolean inviteRequiredForSignup() {
        return !selfHosted() && "github".equalsIgnoreCase(authMode) && inviteRequired;
    }
}