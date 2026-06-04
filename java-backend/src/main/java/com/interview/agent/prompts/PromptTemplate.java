package com.interview.agent.prompts;

import java.time.Instant;

/**
 * prompt_template 表记录。运行时由 {@link PromptService} 按 key 查询并渲染 {var} 占位符。
 */
public record PromptTemplate(
        Long id,
        String key,
        String content,
        String description,
        Instant createdAt,
        Instant updatedAt
) {}
