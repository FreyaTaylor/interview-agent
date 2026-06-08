package com.interview.agent.project.dto;

/**
 * /profile-detail 响应。
 *
 * @param projectFacts  扁平事实列表（JSONB → List&lt;String&gt;）
 * @param version       乐观锁版本号（前端不必使用，调试可见）
 */
public record ProfileResponse(
        Object projectFacts,
        int version
) {
}
