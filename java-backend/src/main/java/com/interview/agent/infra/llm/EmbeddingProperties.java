package com.interview.agent.infra.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Embedding 参数（DashScope，via LangChain4j community 模块）。
 *
 * 命名空间 {@code iagent.embedding}：
 *   - dashscope-api-key  DashScope API Key（与 DeepSeek key 独立）
 *   - model              模型名，默认 text-embedding-v3
 *   - dimension          向量维度，默认 1024（与 V1 schema 对齐）
 */
@ConfigurationProperties(prefix = "iagent.embedding")
public record EmbeddingProperties(
        String dashscopeApiKey,
        String model,
        Integer dimension
) {
    public EmbeddingProperties {
        if (model == null || model.isBlank()) model = "text-embedding-v3";
        if (dimension == null) dimension = 1024;
    }
}
