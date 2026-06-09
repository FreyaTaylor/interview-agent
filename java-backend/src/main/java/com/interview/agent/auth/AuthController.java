package com.interview.agent.auth;

import com.interview.agent.common.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.view.RedirectView;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 认证路由 —— GitHub OAuth 登录 + JWT。
 *
 * <p>与 Python {@code backend/api/auth.py} 对齐：
 * <ul>
 *   <li>{@code GET /api/auth/github}          跳转 GitHub 授权页</li>
 *   <li>{@code GET /api/auth/github/callback} OAuth 回调，换 token 后携带 JWT 重定向回前端</li>
 *   <li>{@code GET /api/auth/me}              用 token 取当前用户信息</li>
 * </ul>
 *
 * <p>本控制器整体在 {@link com.interview.agent.common.WebMvcConfig} 的拦截器放行清单内
 * （{@code /api/auth/**}），因此 /me 需自行从 query 参数解析 token 并校验。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final JwtService jwtService;

    public AuthController(AuthService authService, JwtService jwtService) {
        this.authService = authService;
        this.jwtService = jwtService;
    }

    /** 重定向到 GitHub 授权页。 */
    @GetMapping("/github")
    public RedirectView githubLogin() {
        return new RedirectView(authService.githubAuthorizeUrl());
    }

    /**
     * GitHub OAuth 回调：用 code 换 JWT，成功带 token 跳回前端，失败带 error 跳回。
     */
    @GetMapping("/github/callback")
    public RedirectView githubCallback(@RequestParam("code") String code) {
        String token = authService.oauthCallback(code);
        String frontend = authService.frontendUrl();
        if (token == null) {
            return new RedirectView(frontend + "?error=auth_failed");
        }
        String encoded = URLEncoder.encode(token, StandardCharsets.UTF_8);
        return new RedirectView(frontend + "?token=" + encoded);
    }

    /**
     * 取当前登录用户信息。token 从 {@code ?token=} 读取并校验，无效返回 401。
     */
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<Map<String, Object>>> me(
            @RequestParam(value = "token", required = false) String token) {
        Long userId = jwtService.parseUserId(token);
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error(AuthInterceptor.CODE_UNAUTHORIZED, "未登录或登录已过期"));
        }
        return ResponseEntity.ok(ApiResponse.success(authService.currentUser(userId)));
    }
}
