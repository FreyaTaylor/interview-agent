package com.interview.agent.user.service;

import com.interview.agent.auth.CurrentUser;
import com.interview.agent.user.mapper.UserMapper;
import org.springframework.stereotype.Service;

/**
 * 用户画像服务（对应 Python {@code backend/services/profile.py}）。
 *
 * <p>职责：读 / 整段覆写当前登录用户的纯文本画像（{@code user.profile_text}）。
 * 画像供 ProfilePage 展示编辑，并作为 LLM 上下文（树生成 / 学习 / 答题）个性化输入。
 * user_id 一律取自 {@link CurrentUser}，实现多用户隔离。
 */
@Service
public class UserProfileService {

    private final UserMapper userMapper;

    public UserProfileService(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    /** 取当前用户画像文本；无记录或为 NULL 时返回空串。 */
    public String getProfile() {
        return userMapper.findProfileText(CurrentUser.id()).orElse("");
    }

    /**
     * 整段覆写当前用户画像，返回保存后的文本。
     *
     * @param profileText 新画像（null 视作空串，前后空白会被 trim）
     */
    public String updateProfile(String profileText) {
        String text = profileText == null ? "" : profileText.strip();
        userMapper.updateProfileText(CurrentUser.id(), text);
        return text;
    }
}
