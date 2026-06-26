package com.interview.agent.auth;

import com.interview.agent.user.entity.User;
import com.interview.agent.user.mapper.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * GitHub OAuth 登录服务。
 *
 * <p>与 Python {@code backend/services/user.py} 一一对应：
 * <ol>
 *   <li>{@link #githubAuthorizeUrl(String)} —— 拼授权页 URL（scope=read:user，可带邀请 state）</li>
 *   <li>{@link #oauthCallback(String, String)} —— code 换 token → 拉资料 → 落库 → 签发 JWT</li>
 *   <li>{@link #currentUser(long)} —— /me 取当前用户信息</li>
 * </ol>
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final AuthProperties props;
    private final AppModeProperties mode;
    private final JwtService jwtService;
    private final UserMapper userMapper;
    private final InviteCodeService inviteCodeService;
    private final TransactionTemplate transactionTemplate;
    // GitHub 出站调用专用客户端：国内直连 github.com 易被 RST，若配置了代理则走代理（仅作用于此处）。
    private final RestClient github = buildGithubClient();

    public AuthService(AuthProperties props,
                       AppModeProperties mode,
                       JwtService jwtService,
                       UserMapper userMapper,
                       InviteCodeService inviteCodeService,
                       TransactionTemplate transactionTemplate) {
        this.props = props;
        this.mode = mode;
        this.jwtService = jwtService;
        this.userMapper = userMapper;
        this.inviteCodeService = inviteCodeService;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 构建访问 GitHub 的 RestClient。
     *
     * <p>读取标准代理环境变量（HTTPS_PROXY / HTTP_PROXY / ALL_PROXY，大小写均可），
     * 按 scheme 选择代理类型（{@code socks5://}/{@code socks://} → SOCKS，其余 → HTTP），
     * 用 {@link SimpleClientHttpRequestFactory#setProxy} 让该客户端走代理，以规避国内直连
     * github.com 的 TLS 握手被中断/超时问题。未配置时为直连，且仅影响 GitHub OAuth，
     * 不波及 DeepSeek/DashScope 等国内可直连的服务。
     */
    private static RestClient buildGithubClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(15_000);

        String raw = firstNonBlank(
                System.getenv("HTTPS_PROXY"), System.getenv("https_proxy"),
                System.getenv("ALL_PROXY"), System.getenv("all_proxy"),
                System.getenv("HTTP_PROXY"), System.getenv("http_proxy"));
        Proxy proxy = parseProxy(raw);
        if (proxy != null) {
            factory.setProxy(proxy);
            log.info("GitHub OAuth 出站走代理: {}", proxy);
        }
        return RestClient.builder().requestFactory(factory).build();
    }

    /**
     * 解析代理地址为 {@link Proxy}。
     * 支持 {@code socks5://host:port}、{@code http://user:pass@host:port}、{@code host:port} 等写法；
     * scheme 含 socks 走 {@link Proxy.Type#SOCKS}，否则 {@link Proxy.Type#HTTP}。失败返回 null（直连）。
     */
    private static Proxy parseProxy(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            String s = raw.trim();
            Proxy.Type type = Proxy.Type.HTTP;
            int scheme = s.indexOf("://");
            if (scheme >= 0) {
                if (s.substring(0, scheme).toLowerCase().startsWith("socks")) {
                    type = Proxy.Type.SOCKS;
                }
                s = s.substring(scheme + 3);
            }
            int at = s.indexOf('@');
            if (at >= 0) {
                s = s.substring(at + 1);
            }
            int slash = s.indexOf('/');
            if (slash >= 0) {
                s = s.substring(0, slash);
            }
            int colon = s.lastIndexOf(':');
            String host = colon >= 0 ? s.substring(0, colon) : s;
            int port = colon >= 0 ? Integer.parseInt(s.substring(colon + 1)) : 8080;
            return new Proxy(type, new InetSocketAddress(host, port));
        } catch (Exception e) {
            log.warn("代理地址解析失败，忽略并直连: {}", raw);
            return null;
        }
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    /** GitHub 授权页 URL（用户点击后跳回 /github/callback）。 */
    public String githubAuthorizeUrl(String inviteCode) {
        String state = mode.inviteRequiredForSignup() ? inviteCodeService.createSignedState(inviteCode) : null;
        String url = "https://github.com/login/oauth/authorize"
                + "?client_id=" + encode(props.githubClientId())
                + "&scope=read:user";
        if (state != null) {
            url += "&state=" + encode(state);
        }
        return url;
    }

    /** 回调成功/失败后重定向回的前端地址。 */
    public String frontendUrl() {
        return props.frontendUrl();
    }

    /**
     * OAuth 回调完整流程：code → access_token → GitHub 用户 → 落库 → JWT。
     *
     * @return 签发的 JWT；任一步失败返回 {@code null}
     */
    public String oauthCallback(String code, String state) {
        Map<String, Object> ghUser = exchangeCode(code);
        if (ghUser == null || ghUser.get("id") == null) {
            return null;
        }
        long githubId = ((Number) ghUser.get("id")).longValue();
        String login = (String) ghUser.getOrDefault("login", "");
        String avatar = (String) ghUser.getOrDefault("avatar_url", "");

        User user = userMapper.findByGithubId(githubId).orElse(null);
        long userId;
        String username;
        if (user != null) {
            // 已存在 → 刷新 login/头像（GitHub 资料可能改过）
            userMapper.updateGithubProfile(user.id(), login, avatar);
            userId = user.id();
            username = user.username();
        } else {
            String inviteHash = mode.inviteRequiredForSignup() ? inviteCodeService.parseSignedState(state) : null;
            if (mode.inviteRequiredForSignup() && inviteHash == null) {
                return null;
            }
            userId = transactionTemplate.execute(status -> {
                long insertedId = userMapper.insertGithubUser(githubId, login, avatar);
                if (mode.inviteRequiredForSignup()) {
                    inviteCodeService.consume(inviteHash, insertedId);
                }
                return insertedId;
            });
            if (userId == 0L) {
                return null;
            }
            username = login;
        }
        return jwtService.create(userId, username);
    }

    /** /me：按 user_id 取当前用户信息（前端展示 + 鉴权确认）。 */
    public Map<String, Object> currentUser(long userId) {
        User u = userMapper.findById(userId)
                .orElseThrow(() -> new IllegalStateException("用户不存在: " + userId));
        Map<String, Object> m = new java.util.HashMap<>();
        m.put("id", u.id());
        m.put("username", u.username());
        m.put("github_login", u.githubLogin());
        m.put("avatar_url", u.avatarUrl());
        m.put("profile_text", u.profileText() == null ? "" : u.profileText());
        return m;
    }

    /** 当前认证配置，供前端决定是否展示登录页 / 邀请码输入。 */
    public Map<String, Object> publicConfig() {
        return Map.of(
                "deploy_mode", mode.deployMode(),
                "auth_mode", mode.authMode(),
                "invite_required", mode.inviteRequiredForSignup(),
                "single_user", mode.singleUser()
        );
    }

    /** single_user 模式下返回本地用户。 */
    public Map<String, Object> localUser() {
        return currentUser(1L);
    }

    /**
     * 内部：一次性 code 换 access_token，再用 access_token 拉 GitHub 用户资料。
     * 失败返回 null。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> exchangeCode(String code) {
        try {
            // 第一步：code → access_token
            Map<String, Object> tokenResp = github.post()
                    .uri("https://github.com/login/oauth/access_token")
                    .accept(MediaType.APPLICATION_JSON)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "client_id", props.githubClientId(),
                            "client_secret", props.githubClientSecret(),
                            "code", code))
                    .retrieve()
                    .body(Map.class);
            Object accessToken = tokenResp == null ? null : tokenResp.get("access_token");
            if (accessToken == null) {
                log.error("GitHub token 交换失败: {}", tokenResp);
                return null;
            }

            // 第二步：access_token → 用户资料
            return github.get()
                    .uri("https://api.github.com/user")
                    .header("Authorization", "Bearer " + accessToken)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(Map.class);
        } catch (Exception e) {
            log.error("GitHub OAuth 调用异常: {}", e.getMessage(), e);
            return null;
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
