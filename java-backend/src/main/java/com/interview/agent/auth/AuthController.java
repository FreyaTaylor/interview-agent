package com.interview.agent.auth;

import com.interview.agent.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Collections;
import java.util.Map;

/**
 * 临时 Mock Auth — 一期 user_id 固定为 1，跳过 GitHub OAuth。
 * S9 实现真正的 OAuth 后替换本类。
 *
 * <p>前端 AuthContext 调用 /api/auth/me 即可拿到固定用户，进入主界面。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    /** 一期固定 mock 用户；S9 接 OAuth 后整体替换。 */
    private static final Map<String, Object> MOCK_USER;

    static {
        Map<String, Object> u = new HashMap<>();
        u.put("id", 1);
        u.put("login", "dev");
        u.put("name", "开发者");
        u.put("avatar_url", null);
        MOCK_USER = Collections.unmodifiableMap(u);
    }

    @GetMapping("/me")
    public ApiResponse<Map<String, Object>> me() {
        return ApiResponse.success(MOCK_USER);
    }
}
