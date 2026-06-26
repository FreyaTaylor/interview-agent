package com.interview.agent.auth;

import com.interview.agent.common.BizException;
import com.interview.agent.user.entity.User;
import com.interview.agent.user.mapper.UserMapper;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** 邀请码签名、校验、原子消耗与运营管理。 */
@Service
public class InviteCodeService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final long STATE_TTL_SECONDS = 600;

    private final InviteCodeMapper inviteCodeMapper;
    private final UserMapper userMapper;
    private final AuthProperties authProperties;

    public InviteCodeService(InviteCodeMapper inviteCodeMapper,
                             UserMapper userMapper,
                             AuthProperties authProperties) {
        this.inviteCodeMapper = inviteCodeMapper;
        this.userMapper = userMapper;
        this.authProperties = authProperties;
    }

    /** 把用户输入的邀请码校验后封装成带签名 OAuth state。 */
    public String createSignedState(String inviteCode) {
        String codeHash = hashInviteCode(inviteCode);
        if (!inviteCodeMapper.existsUsable(codeHash)) {
            throw new BizException(40001, "邀请码无效或已被使用");
        }
        long expiresAt = Instant.now().getEpochSecond() + STATE_TTL_SECONDS;
        String payload = codeHash + ":" + expiresAt;
        return base64Url(payload + ":" + hmac(payload));
    }

    /** 校验 OAuth state，返回邀请码 hash；无效返回 null。 */
    public String parseSignedState(String state) {
        if (state == null || state.isBlank()) {
            return null;
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(state), StandardCharsets.UTF_8);
            String[] parts = raw.split(":", 3);
            if (parts.length != 3) {
                return null;
            }
            String payload = parts[0] + ":" + parts[1];
            if (!MessageDigest.isEqual(parts[2].getBytes(StandardCharsets.UTF_8), hmac(payload).getBytes(StandardCharsets.UTF_8))) {
                return null;
            }
            long expiresAt = Long.parseLong(parts[1]);
            if (Instant.now().getEpochSecond() > expiresAt) {
                return null;
            }
            return parts[0];
        } catch (Exception e) {
            return null;
        }
    }

    /** 消耗邀请码，失败时抛业务异常并让事务回滚。 */
    public void consume(String codeHash, long userId) {
        if (codeHash == null || inviteCodeMapper.consume(codeHash, userId) != 1) {
            throw new BizException(40001, "邀请码无效或已被使用");
        }
    }

    /** 生成邀请码；仅 admin 可用。 */
    public List<Map<String, Object>> createCodes(int count, String note, LocalDateTime expiresAt) {
        requireAdmin();
        int n = Math.max(1, Math.min(count, 100));
        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String code = newPlainCode();
            inviteCodeMapper.insert(hashInviteCode(code), note, CurrentUser.id(), expiresAt);
            result.add(Map.of("code", code));
        }
        return result;
    }

    /** 最近邀请码列表；不返回明文 code。 */
    public List<InviteCode> listRecent(int limit) {
        requireAdmin();
        return inviteCodeMapper.listRecent(Math.max(1, Math.min(limit, 200)));
    }

    private void requireAdmin() {
        User user = userMapper.findById(CurrentUser.id())
                .orElseThrow(() -> new BizException(40100, "未登录或登录已过期"));
        if (!"admin".equalsIgnoreCase(user.role())) {
            throw new BizException(40300, "仅管理员可以管理邀请码");
        }
    }

    private String newPlainCode() {
        byte[] bytes = new byte[18];
        RANDOM.nextBytes(bytes);
        return "ia-" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hashInviteCode(String inviteCode) {
        if (inviteCode == null || inviteCode.isBlank()) {
            throw new BizException(40001, "请输入邀请码");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(inviteCode.trim().toLowerCase().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (Exception e) {
            throw new BizException(50000, "邀请码处理失败", e);
        }
    }

    private String hmac(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(authProperties.jwtSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new BizException(50000, "邀请码签名失败", e);
        }
    }

    private String base64Url(String raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }
}