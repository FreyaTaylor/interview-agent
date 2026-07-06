package com.interview.agent.learn.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Learn 模块「子话题生成」策略配置。
 *
 * <p>命名空间 {@code iagent.learn.subtopics}：
 * <ul>
 *   <li>{@code strategy} —— 生成策略：{@code single}（单次 LLM，旧逻辑，默认）
 *       / {@code two-step}（生成 + 审校去重补全两步 workflow）。</li>
 * </ul>
 *
 * <p>默认 {@code single} 以保持与历史行为一致；验证 two-step 效果后再切换，可随时改配置回滚。
 */
@ConfigurationProperties(prefix = "iagent.learn.subtopics")
public record LearnSubtopicsProperties(String strategy) {

    public LearnSubtopicsProperties {
        if (strategy == null || strategy.isBlank()) {
            strategy = "single";
        }
    }

    /** 是否启用"两步生成"（生成 + 审校去重补全）。 */
    public boolean twoStep() {
        return "two-step".equalsIgnoreCase(strategy);
    }
}
