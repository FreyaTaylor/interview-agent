package com.interview.agent.learn.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Step B 单子话题正文请求体（POST /api/learn/subtopic-content）。
 *
 * @param subtopicId 子话题 id
 * @param action     {@code fetch}（pending 则生成，ready 直接返回）/ {@code regenerate}（重生正文）
 */
public record SubtopicContentRequest(
        @NotNull Long subtopicId,
        @NotBlank String action
) {
    /** 复用 {@link LearnAssetRequest.Action}；未知值抛 40001。 */
    public LearnAssetRequest.Action resolvedAction() {
        if (LearnAssetRequest.ACTION_FETCH.equalsIgnoreCase(action)) {
            return LearnAssetRequest.Action.FETCH;
        }
        if (LearnAssetRequest.ACTION_REGENERATE.equalsIgnoreCase(action)) {
            return LearnAssetRequest.Action.REGENERATE;
        }
        throw new com.interview.agent.common.BizException(40001,
                "未知 action: " + action + "，仅支持 fetch / regenerate");
    }
}
