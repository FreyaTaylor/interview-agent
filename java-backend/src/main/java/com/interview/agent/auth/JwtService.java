package com.interview.agent.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

/**
 * JWT 签发与校验（HS256）。
 *
 * <p>与 Python 端 {@code backend/services/user.py} 严格对齐：
 * <ul>
 *   <li>算法 HS256，密钥取自 {@code iagent.auth.jwt-secret}（须与 Python JWT_SECRET 相同）</li>
 *   <li>payload 三个字段：{@code user_id}（int）、{@code username}（str）、{@code exp}</li>
 *   <li>过期/签名无效 → 返回 null，不抛异常（与 Python decode_jwt 行为一致）</li>
 * </ul>
 */
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    private final SecretKey key;
    private final int expireDays;

    public JwtService(AuthProperties props) {
        if (props.jwtSecret() == null || props.jwtSecret().isBlank()) {
            throw new IllegalStateException("iagent.auth.jwt-secret 未配置，无法签发/校验 JWT");
        }
        // HS256 要求密钥 ≥ 256 bit；Python 用任意字符串做 secret，这里按 UTF-8 字节构造，
        // 与 PyJWT 的 HMAC(key.encode()) 字节一致，保证两端 token 互通。
        this.key = Keys.hmacShaKeyFor(props.jwtSecret().getBytes(StandardCharsets.UTF_8));
        this.expireDays = props.jwtExpireDays();
    }

    /** 签发 token：payload = {user_id, username, exp(now + expireDays)}。 */
    public String create(long userId, String username) {
        Instant now = Instant.now();
        return Jwts.builder()
                .claim("user_id", userId)
                .claim("username", username)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(expireDays, ChronoUnit.DAYS)))
                .signWith(key)
                .compact();
    }

    /**
     * 校验并解析 token，取出 user_id。
     *
     * @return 解析出的 user_id；token 缺失/过期/签名错误时返回 {@code null}
     */
    public Long parseUserId(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            // PyJWT 写入的是数字，jjwt 取出可能是 Integer/Long，统一转 long
            Number uid = claims.get("user_id", Number.class);
            return uid == null ? null : uid.longValue();
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("JWT 校验失败: {}", e.getMessage());
            return null;
        }
    }
}
