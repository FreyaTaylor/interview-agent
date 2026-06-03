package com.interview.agent.infra.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * LLM Chat 参数（DeepSeek，via Spring AI OpenAI starter）。
 *
 * 命名空间 {@code iagent.llm}：
 *   - chat-temperature-score    评分类提示词温度（默认 0.0，追求稳定）
 *   - chat-temperature-generate 生成类提示词温度（默认 0.6，追求多样）
 */
@ConfigurationProperties(prefix = "iagent.llm")
public record LlmProperties(
        Double chatTemperatureScore,
        Double chatTemperatureGenerate
) {
    public LlmProperties {
        if (chatTemperatureScore == null) chatTemperatureScore = 0.0;
        if (chatTemperatureGenerate == null) chatTemperatureGenerate = 0.6;
    }
}
