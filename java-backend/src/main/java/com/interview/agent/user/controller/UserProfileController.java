package com.interview.agent.user.controller;

import com.interview.agent.user.dto.ProfileUpdateRequest;
import com.interview.agent.user.service.UserProfileService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 用户画像 API（对应 Python 端 backend.api.profile）。
 *
 * 路径：/api/user
 *   GET /profile → 读当前用户画像 {profile_text}
 *   PUT /profile → 整段覆写画像，返回保存后的 {profile_text}
 *
 * 调用方：前端 ProfilePage（加载展示 + "保存" 按钮）。
 */
@RestController
@RequestMapping("/api/user")
public class UserProfileController {

    private final UserProfileService service;

    public UserProfileController(UserProfileService service) {
        this.service = service;
    }

    @GetMapping("/profile")
    public Map<String, Object> getProfile() {
        return Map.of("profile_text", service.getProfile());
    }

    @PutMapping("/profile")
    public Map<String, Object> updateProfile(@RequestBody ProfileUpdateRequest req) {
        String saved = service.updateProfile(req.profileText());
        return Map.of("profile_text", saved);
    }
}
