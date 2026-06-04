package com.interview.agent.auth;

import com.interview.agent.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 临时 Mock Auth — 一期 user_id 固定为 1，跳过 GitHub OAuth。
 * S9 实现真正的 OAuth 后替换本类。
 *
 * 前端 AuthContext 调用 /api/auth/me 即可拿到固定用户，进入主界面。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @GetMapping("/me")
    public ApiResponse<Map<String, Object>> me() {
        Map<String, Object> user = new HashMap<>();
        user.put("id", 1);
        user.put("login", "dev");
        user.put("name", "开发者");
        user.put("avatar_url", null);
        return ApiResponse.success(user);
    }
}
