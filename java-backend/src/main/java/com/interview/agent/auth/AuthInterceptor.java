package com.interview.agent.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.interview.agent.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;

/**
 * 登录态拦截器：从请求里解析 JWT，写入 {@link CurrentUser} 上下文。
 *
 * <p>token 来源（按优先级）：
 * <ol>
 *   <li>{@code Authorization: Bearer <token>} 请求头（普通 fetch/axios）</li>
 *   <li>{@code ?token=<token>} 查询参数（OAuth 回调重定向、EventSource 等无法带头的场景）</li>
 * </ol>
 *
 * <p>放行清单由 {@link com.interview.agent.common.WebMvcConfig} 的
 * {@code excludePathPatterns} 控制（公开登录链路）。
 * 走到本拦截器的都是受保护接口：无有效 token 直接返回 401 统一包裹体。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    /** 未登录错误码（与 Python 401 语义对齐，沿用 4xxxx 段）。 */
    public static final int CODE_UNAUTHORIZED = 40100;

    private final JwtService jwtService;
    private final AppModeProperties mode;
    private final ObjectMapper objectMapper;

    public AuthInterceptor(JwtService jwtService, AppModeProperties mode, ObjectMapper objectMapper) {
        this.jwtService = jwtService;
        this.mode = mode;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        // CORS 预检直接放行
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        if (mode.singleUser()) {
            CurrentUser.set(1L);
            return true;
        }

        String token = extractToken(request);
        Long userId = jwtService.parseUserId(token);
        if (userId == null) {
            writeUnauthorized(response);
            return false;
        }
        CurrentUser.set(userId);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        // 虚拟线程会复用底层载体，务必清理，避免上下文串号
        CurrentUser.clear();
    }

    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7).trim();
        }
        String q = request.getParameter("token");
        return q != null && !q.isBlank() ? q.trim() : null;
    }

    private void writeUnauthorized(HttpServletResponse response) throws Exception {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        ApiResponse<Void> body = ApiResponse.error(CODE_UNAUTHORIZED, "未登录或登录已过期");
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
