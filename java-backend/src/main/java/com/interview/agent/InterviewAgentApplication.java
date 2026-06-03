package com.interview.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 入口类。
 *
 * {@link ConfigurationPropertiesScan} 自动扫描 {@code com.interview.agent} 下所有
 * {@code @ConfigurationProperties} Record（LlmProperties、EmbeddingProperties 等），
 * 无需逐个 {@code @EnableConfigurationProperties}。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class InterviewAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(InterviewAgentApplication.class, args);
    }
}
