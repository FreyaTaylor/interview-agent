package com.interview.agent.common;

import com.interview.agent.auth.AuthInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 横切配置：CORS + 登录态拦截。
 *
 * <p>允许来源从 {@code iagent.cors.allowed-origins}（逗号分隔）读取，
 * 使用 origin pattern 以支持本地任意端口（如 Vite 自动切到 5174/5175）。
 *
 * <p>{@link AuthInterceptor} 拦截 {@code /api/**}，仅放行公开登录链路；
 * 邀请码管理等接口仍必须携带有效 JWT。single_user 模式下拦截器固定写入 user_id=1。
 *
 * <p>生产环境务必收紧 allowed-origins，并考虑去掉 {@code allowCredentials=true}
 * 或换成显式 origin 列表（{@code *} + credentials 是非法组合）。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final String[] origins;
    private final AuthInterceptor authInterceptor;

    public WebMvcConfig(@Value("${iagent.cors.allowed-origins:}") String allowedOrigins,
                        AuthInterceptor authInterceptor) {
        // 逗号分隔 → 数组；空串 → 空数组（addCorsMappings 会自动跳过）
        this.origins = allowedOrigins == null || allowedOrigins.isBlank()
                ? new String[0]
                : allowedOrigins.split("\\s*,\\s*");
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/api/auth/config",
                        "/api/auth/github",
                        "/api/auth/github/callback",
                        "/api/auth/me");
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
