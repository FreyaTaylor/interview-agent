package com.interview.agent.infra.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.interview.agent.common.BizException;
import com.interview.agent.common.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * DashScope 多模态（qwen-vl-max）视觉调用客户端。
 *
 * <p>用途：把截图（思维导图 / 大纲）连同 prompt 一起发给视觉大模型，让其识别为结构化文本。
 * 复刻 Python {@code create_tree_from_image} 里用 langchain_openai.ChatOpenAI(qwen-vl-max) 走
 * DashScope <b>OpenAI 兼容端点</b>的方式。
 *
 * <p>为什么不复用 {@link org.springframework.ai.chat.client.ChatClient}：项目里唯一的 ChatClient
 * 由 Spring AI 自动装配指向 DeepSeek（纯文本，无视觉能力），且模型名固定。视觉是另一家
 * （DashScope）+ 另一个模型，故单开一个轻量 HttpClient 直连，与 ASR 实现一致。
 *
 * <p>密钥复用 {@link EmbeddingProperties#dashscopeApiKey()}（embedding 与视觉同属 DashScope）。
 */
@Component
public class QwenVisionClient {

    private static final Logger log = LoggerFactory.getLogger(QwenVisionClient.class);

    /** DashScope OpenAI 兼容 chat/completions 端点（与 Python base_url 一致）。 */
    private static final String CHAT_URL =
            "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";
    /** 视觉模型名（与 Python 对齐）。 */
    private static final String MODEL = "qwen-vl-max";
    /** 输出 token 上限：知识树 JSON 可能较长，必须放开默认上限，否则会被截断导致括号不匹配。 */
    private static final int MAX_TOKENS = 8192;
    /** 单次请求超时：截图体积大 + 视觉推理慢，120s 容易超时，放宽到 180s。 */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(180);

    private final EmbeddingProperties props;
    private final HttpClient httpClient;

    public QwenVisionClient(EmbeddingProperties props) {
        this.props = props;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    /**
     * 调用视觉模型解析一张图片，返回模型输出的原始文本（通常是带 JSON 的字符串）。
     *
     * @param imageBase64 图片的 base64（不含 data: 前缀）
     * @param mediaType   图片 MIME（如 image/png），用于拼 data URL
     * @param prompt      文本指令（解析规则 + 输出格式约束）
     * @param temperature 采样温度
     * @return 模型回复的纯文本内容（choices[0].message.content）
     * @throws BizException 50000 未配置密钥 / 网络异常 / 响应体异常
     */
    public String parseImage(String imageBase64, String mediaType, String prompt, double temperature) {
        String apiKey = props.dashscopeApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new BizException(50000, "视觉解析未配置：请设置 DASHSCOPE_API_KEY");
        }

        // 用 Jackson 组装请求体，保证 prompt / base64 里的特殊字符被正确转义。
        String dataUrl = "data:" + mediaType + ";base64," + imageBase64;
        Map<String, Object> body = Map.of(
                "model", MODEL,
                "temperature", temperature,
                "max_tokens", MAX_TOKENS,
                "messages", List.of(Map.of(
                        "role", "user",
                        "content", List.of(
                                Map.of("type", "image_url",
                                        "image_url", Map.of("url", dataUrl)),
                                Map.of("type", "text", "text", prompt)
                        )
                ))
        );
        String payload = JsonUtil.toJson(body);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(CHAT_URL))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> resp;
        try {
            resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new BizException(50000, "视觉模型请求失败: " + e.getMessage(), e);
        }
        if (resp.statusCode() / 100 != 2) {
            log.warn("[Vision] qwen-vl 返回非 2xx: status={} body={}", resp.statusCode(), resp.body());
            throw new BizException(50000, "视觉模型返回异常状态: " + resp.statusCode());
        }

        // 解析 OpenAI 兼容响应：choices[0].message.content
        JsonNode root = JsonUtil.fromJson(resp.body(), JsonNode.class);
        JsonNode content = root.path("choices").path(0).path("message").path("content");
        if (content.isMissingNode() || content.isNull()) {
            throw new BizException(50000, "视觉模型响应缺少 content 字段");
        }
        return content.asText();
    }
}
