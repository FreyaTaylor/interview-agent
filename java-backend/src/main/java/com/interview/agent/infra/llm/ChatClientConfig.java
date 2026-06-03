package com.interview.agent.infra.llm;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring AI ChatClient 装配。
 *
 * 实际的 OpenAI ChatModel（指向 DeepSeek 兼容端点）由
 * {@code spring-ai-starter-model-openai} 通过 {@code spring.ai.openai.*} 自动装配；
 * 这里只把 Builder 包成单例 ChatClient 供业务侧注入。
 *
 * 业务侧使用：
 * <pre>
 *   String answer = chatClient.prompt()
 *       .system(systemPrompt)
 *       .user(userPrompt)
 *       .call()
 *       .content();
 * </pre>
 */
@Configuration
public class ChatClientConfig {

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder.build();
    }
}
