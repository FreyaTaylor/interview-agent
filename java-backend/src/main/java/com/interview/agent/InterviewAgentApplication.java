package com.interview.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * 入口类。
 *
 * {@link ConfigurationPropertiesScan} 自动扫描 {@code com.interview.agent} 下所有
 * {@code @ConfigurationProperties} Record（LlmProperties、EmbeddingProperties 等），
 * 无需逐个 {@code @EnableConfigurationProperties}。
 *
 * <p>{@link EnableAsync} 启用 {@code @Async} 方法异步执行（S7.3 项目画像抽取 fire-and-forget）；
 * 默认使用 Spring Boot 自动配置的 {@code SimpleAsyncTaskExecutor}（每次新线程，无池化）。
 * 项目侧异步任务量极低（每次拷打 finish 后触发一次），未做线程池调优。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableAsync
public class InterviewAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(InterviewAgentApplication.class, args);
    }
}
