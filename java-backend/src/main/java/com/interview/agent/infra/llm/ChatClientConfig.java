package com.interview.agent.infra.llm;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

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

    /**
     * 显式放宽 LLM HTTP 客户端的读超时。
     *
     * <p><b>为什么必须在这里配</b>：Spring Boot 3.3.x 还不支持 {@code spring.http.client.read-timeout}
     * 属性（该属性 3.4+ 才生效），所以 application.yml 里的 180s 被静默忽略；
     * 而 classpath 上的 OkHttp 会被 Spring AI 的 OpenAI RestClient 选用，其默认读超时仅 10s。
     * DeepSeek 解析长面试文本（maxTokens=4096）耗时常超 10s，导致
     * {@code SocketTimeoutException} → 3 次重试全失败 → 返回空 groups → 全部落「未归属」。</p>
     *
     * <p>通过 {@link RestClientCustomizer} 给 Boot 自动装配、并被 Spring AI 复用的
     * {@code RestClient.Builder} 设置请求工厂超时（连接 30s / 读 180s），
     * 对解析 / 评分 / ASR 归一等所有 LLM 调用统一生效。</p>
     */
    @Bean
    public RestClientCustomizer llmHttpTimeoutCustomizer() {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(Duration.ofSeconds(30))
                .withReadTimeout(Duration.ofSeconds(180));
        return restClientBuilder ->
                restClientBuilder.requestFactory(ClientHttpRequestFactories.get(settings));
    }
}
