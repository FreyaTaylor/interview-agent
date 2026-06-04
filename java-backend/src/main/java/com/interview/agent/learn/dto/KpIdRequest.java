package com.interview.agent.learn.dto;

import jakarta.validation.constraints.NotNull;

/** 只带 kpId 的请求体（如 chat-history）。 */
public record KpIdRequest(@NotNull Long kpId) {
}
