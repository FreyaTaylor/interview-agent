package com.interview.agent.interview.leetcode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * LeetCode 题库查询客户端 —— 单点封装 {@code leetcode.com/graphql} 的搜索。
 *
 * <p>只用国际站：国区 {@code leetcode.cn} 被 Cloudflare 拦（403 人机校验），已在 spec 验证。
 *
 * <p><b>失败即降级</b>：网络不可达 / 超时 / 结构变更 → 返回空列表并 warn，<b>绝不抛异常</b>，
 * 以免阻断面试解析主流程（算法题退化为无链接）。
 */
@Component
public class LeetCodeClient {

    private static final Logger log = LoggerFactory.getLogger(LeetCodeClient.class);

    private static final String GRAPHQL = "https://leetcode.com/graphql";

    /** 按关键词搜题；只取展示所需的题号/题名/slug/难度。 */
    private static final String SEARCH_QUERY = """
            query($kw:String!,$limit:Int!){
              questionList(categorySlug:"",limit:$limit,skip:0,filters:{searchKeywords:$kw}){
                data{questionFrontendId title titleSlug difficulty}
              }
            }
            """;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestClient http;

    public LeetCodeClient() {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(Duration.ofSeconds(5))
                .withReadTimeout(Duration.ofSeconds(8));
        this.http = RestClient.builder()
                .requestFactory(ClientHttpRequestFactories.get(settings))
                .baseUrl(GRAPHQL)
                .defaultHeader("Content-Type", "application/json")
                .defaultHeader("Referer", "https://leetcode.com")
                .build();
    }

    /**
     * 按关键词搜索 LeetCode 题目。
     *
     * @param keyword 搜索词（英文题名/关键词效果最好，如 "LRU"、"two sum"）
     * @param limit   返回条数上限
     * @return 候选题目；查询失败或无结果返回空列表（不抛）
     */
    public List<LeetCodeQuestion> search(String keyword, int limit) {
        if (keyword == null || keyword.isBlank()) {
            return List.of();
        }
        try {
            Map<String, Object> body = Map.of(
                    "query", SEARCH_QUERY,
                    "variables", Map.of("kw", keyword, "limit", Math.max(1, limit))
            );
            String raw = http.post().body(body).retrieve().body(String.class);
            return parse(raw);
        } catch (Exception e) {
            log.warn("[LeetCode] 搜索失败 kw='{}': {}", keyword, e.getMessage());
            return List.of();
        }
    }

    /** 解析 GraphQL 响应 {@code data.questionList.data[]}。 */
    private static List<LeetCodeQuestion> parse(String raw) throws Exception {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        JsonNode arr = MAPPER.readTree(raw)
                .path("data").path("questionList").path("data");
        if (!arr.isArray()) {
            return List.of();
        }
        List<LeetCodeQuestion> out = new ArrayList<>(arr.size());
        for (JsonNode q : arr) {
            String slug = q.path("titleSlug").asText(null);
            if (slug == null || slug.isBlank()) {
                continue;
            }
            out.add(new LeetCodeQuestion(
                    q.path("questionFrontendId").asText(null),
                    q.path("title").asText(null),
                    slug,
                    q.path("difficulty").asText(null)
            ));
        }
        return out;
    }

    /** 一道 LeetCode 题的最小信息。 */
    public record LeetCodeQuestion(String id, String title, String slug, String difficulty) {
        /** 题目页链接（中文站；slug 与国际站通用）。 */
        public String url() {
            return "https://leetcode.cn/problems/" + slug + "/description/";
        }
    }
}
