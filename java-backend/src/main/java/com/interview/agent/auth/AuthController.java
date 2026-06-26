package com.interview.agent.auth;

import com.interview.agent.common.ApiResponse;
import com.interview.agent.common.BizException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.view.RedirectView;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
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
 * <p>公开登录链路在 {@link com.interview.agent.common.WebMvcConfig} 的拦截器放行清单内；
 * 管理邀请码接口仍受拦截器保护。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final JwtService jwtService;
    private final AppModeProperties mode;
    private final InviteCodeService inviteCodeService;

    public AuthController(AuthService authService,
                          JwtService jwtService,
                          AppModeProperties mode,
                          InviteCodeService inviteCodeService) {
        this.authService = authService;
        this.jwtService = jwtService;
        this.mode = mode;
        this.inviteCodeService = inviteCodeService;
    }

    /** 前端启动时读取认证模式。 */
    @GetMapping("/config")
    public ApiResponse<Map<String, Object>> config() {
        return ApiResponse.success(authService.publicConfig());
    }

    /** 重定向到 GitHub 授权页。 */
    @GetMapping("/github")
    public RedirectView githubLogin(@RequestParam(value = "invite_code", required = false) String inviteCode) {
        try {
            return new RedirectView(authService.githubAuthorizeUrl(inviteCode));
        } catch (BizException e) {
            return new RedirectView(authService.frontendUrl() + "?error=" + encode(e.getMessage()));
        }
    }

    /**
     * GitHub OAuth 回调：用 code 换 JWT，成功带 token 跳回前端，失败带 error 跳回。
     */
    @GetMapping("/github/callback")
    public RedirectView githubCallback(@RequestParam("code") String code,
                                       @RequestParam(value = "state", required = false) String state) {
        String token = authService.oauthCallback(code, state);
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
        if (mode.singleUser()) {
            return ResponseEntity.ok(ApiResponse.success(authService.localUser()));
        }
        Long userId = jwtService.parseUserId(token);
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error(AuthInterceptor.CODE_UNAUTHORIZED, "未登录或登录已过期"));
        }
        return ResponseEntity.ok(ApiResponse.success(authService.currentUser(userId)));
    }

    /** 生成邀请码；需已登录管理员。 */
    @PostMapping("/invite-codes/create")
    public ApiResponse<Map<String, Object>> createInviteCodes(@RequestBody CreateInviteCodesReq req) {
        int count = req.count() == null ? 1 : req.count();
        return ApiResponse.success(Map.of("codes", inviteCodeService.createCodes(count, req.note(), req.expiresAt())));
    }

    /** 查看最近邀请码状态；需已登录管理员。 */
    @PostMapping("/invite-codes/list")
    public ApiResponse<Map<String, Object>> listInviteCodes(@RequestBody(required = false) ListInviteCodesReq req) {
        int limit = req == null || req.limit() == null ? 50 : req.limit();
        return ApiResponse.success(Map.of("items", inviteCodeService.listRecent(limit)));
    }

    public record CreateInviteCodesReq(Integer count, String note, LocalDateTime expiresAt) {
    }

    public record ListInviteCodesReq(Integer limit) {
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
