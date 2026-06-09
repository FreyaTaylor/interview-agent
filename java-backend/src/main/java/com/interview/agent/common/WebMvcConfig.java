package com.interview.agent.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 横切配置：当前只开 CORS（给本地前端联调用）。
 *
 * <p>允许来源从 {@code iagent.cors.allowed-origins}（逗号分隔）读取，
 * 使用 origin pattern 以支持本地任意端口（如 Vite 自动切到 5174/5175）。
 *
 * <p>生产环境务必收紧 allowed-origins，并考虑去掉 {@code allowCredentials=true}
 * 或换成显式 origin 列表（{@code *} + credentials 是非法组合）。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final String[] origins;

    public WebMvcConfig(@Value("${iagent.cors.allowed-origins:}") String allowedOrigins) {
        // 逗号分隔 → 数组；空串 → 空数组（addCorsMappings 会自动跳过）
        this.origins = allowedOrigins == null || allowedOrigins.isBlank()
                ? new String[0]
                : allowedOrigins.split("\\s*,\\s*");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        if (origins.length == 0) {
            return;
        }
        registry.addMapping("/api/**")
            .allowedOriginPatterns(origins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
