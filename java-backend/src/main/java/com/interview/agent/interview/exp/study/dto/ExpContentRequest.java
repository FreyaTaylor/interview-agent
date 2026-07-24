package com.interview.agent.interview.exp.study.dto;

import com.interview.agent.common.BizException;

/**
 * 「看看面经」问题内容请求。
 *
 * @param questionId 问题节点 id
 * @param action     fetch（pending 才生、ready 直读）| regenerate（强制重生）；默认 fetch
 */
public record ExpContentRequest(long questionId, String action) {

    /** 解析 action；空视为 fetch；未知抛 40001。 */
    public String resolvedAction() {
        String a = action == null || action.isBlank() ? "fetch" : action.strip();
        if (!"fetch".equals(a) && !"regenerate".equals(a)) {
            throw new BizException(40001, "action 只能是 fetch / regenerate");
        }
        return a;
    }
}
