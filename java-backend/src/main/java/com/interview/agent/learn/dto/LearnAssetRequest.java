package com.interview.agent.learn.dto;

import com.interview.agent.common.BizException;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Learn 资源请求体（讲解 / 题目共用）。
 *
 * <p>项目约定全 POST + body 传参（详见仓库 java-style.md "API 形式"）。
 *
 * @param kpId   知识点 id
 * @param action {@link Action#FETCH}（取，无则生成） / {@link Action#REGENERATE}（强制重生）
 */
public record LearnAssetRequest(
        @NotNull Long kpId,
        @NotBlank String action
) {
    public static final String ACTION_FETCH = "fetch";
    public static final String ACTION_REGENERATE = "regenerate";

    /** action 枚举。 */
    public enum Action { FETCH, REGENERATE }

    /** 解析 action 字段，未知值抛 40001。Service 层调用。 */
    public Action resolvedAction() {
        if (ACTION_FETCH.equalsIgnoreCase(action)) return Action.FETCH;
        if (ACTION_REGENERATE.equalsIgnoreCase(action)) return Action.REGENERATE;
        throw new BizException(40001, "未知 action: " + action + "，仅支持 fetch / regenerate");
    }
}

