package com.interview.agent.interview;

import com.interview.agent.auth.CurrentUserTestSupport;
import com.interview.agent.common.LlmInvoker;
import com.interview.agent.infra.llm.EmbeddingProperties;
import com.interview.agent.infra.llm.EmbeddingService;
import com.interview.agent.interview.matcher.InterviewNodeMatcher;
import com.interview.agent.prompts.PromptKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 【DEMO】知识点匹配「跨域错配」专项测试 —— 验证 A+B 修复。
 *
 * <p>知识点匹配是<b>扁平全树向量召回 + LLM rerank</b>：向量近 ≠ 语义对，"Spring 事务"和"Redis 事务"
 * 向量很近却是不同技术域。修复：
 * <ul>
 *   <li><b>A</b>：rerank 候选串带「父分类 / 名」（如 {@code Redis / 事务与lua脚本}）给 LLM 域信息；
 *       prompt(V60) 要求跨域判 null。</li>
 *   <li><b>B</b>：LLM 判跨域返 null 时，在「未命名知识点」下建占位叶子（embedding 用 {@code 路径/名} 与建树一致）。</li>
 * </ul>
 *
 * <p>隔离手段：用一个专属 userId（避免真库 63 个真实知识点污染最近邻）；假 EmbeddingService 让所有文本
 * 同向量（两个候选距离都为 0，都进候选，逼 LLM 靠"域"区分）；可编程假 LlmInvoker 捕获候选串并按脚本裁决。
 */
@SpringBootTest
@Import(KnowledgeMatchDemoIT.FakeBeans.class)
@Transactional
class KnowledgeMatchDemoIT {

    static final String VEC = "[1" + ",0".repeat(1023) + "]";
    static final long USER = 424242L;   // 专属隔离用户，绕开真库既有知识点

    @TestConfiguration
    static class FakeBeans {
        @Bean @Primary
        EmbeddingService fakeEmbeddingService() {
            return new EmbeddingService(new EmbeddingProperties(null, "text-embedding-v3", 1024)) {
                @Override public String embedToLiteral(String text) { return VEC; }
            };
        }

        @Bean @Primary
        ProgrammableLlm programmableLlm() { return new ProgrammableLlm(); }
    }

    /** 可编程假 LLM：捕获 rerank 候选串，按 rerankResponder 脚本返回 JSON。 */
    static class ProgrammableLlm extends LlmInvoker {
        volatile String lastCandidates;
        volatile Function<Map<String, Object>, String> rerankResponder = vars -> "{\"node_id\": null}";
        ProgrammableLlm() { super(null, null); }

        @Override
        public <T> Optional<T> invoke(Spec spec, ResponseParser<T> parser) {
            if (PromptKeys.INTERVIEW_MATCH_KNOWLEDGE_RERANK.equals(spec.promptKey())) {
                lastCandidates = String.valueOf(spec.vars().get("candidates"));
                try {
                    return Optional.of(parser.parse(rerankResponder.apply(spec.vars())));
                } catch (Exception e) {
                    return Optional.empty();
                }
            }
            return Optional.empty();   // 本 DEMO 不触发其它 LLM 调用
        }
    }

    @Autowired private InterviewNodeMatcher matcher;
    @Autowired private ProgrammableLlm llm;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() { CurrentUserTestSupport.set(USER); }

    @AfterEach
    void tearDown() { CurrentUserTestSupport.clear(); }

    private long seedCategory(String name) {
        return jdbc.queryForObject(
                "INSERT INTO tree_node (tree_kind, user_id, parent_id, name, level, node_type, interview_weight, sort_order) "
                        + "VALUES ('knowledge', ?, NULL, ?, 1, 'category', 3, 0) RETURNING id",
                Long.class, USER, name);
    }

    private long seedKp(long categoryId, String name) {
        return jdbc.queryForObject(
                "INSERT INTO tree_node (tree_kind, user_id, parent_id, name, level, node_type, interview_weight, sort_order, embedding) "
                        + "VALUES ('knowledge', ?, ?, ?, 2, 'knowledge_point', 3, 0, ?::vector) RETURNING id",
                Long.class, USER, categoryId, name, VEC);
    }

    private Map<String, Object> knowledgeGroup(String kpText) {
        return Map.of("type", "knowledge", "knowledge_point", kpText);
    }

    // ---- 从候选串里挑「含某关键词」那一行的 id，模拟"域感知"的 LLM ----
    private static Long pickIdByDomain(String candidates, String domainKeyword) {
        for (String line : candidates.split("\n")) {
            if (line.contains(domainKeyword)) {
                Matcher m = Pattern.compile("id=(\\d+)").matcher(line);
                if (m.find()) return Long.parseLong(m.group(1));
            }
        }
        return null;
    }

    /**
     * 用例 1（fix A）：两个不同域的候选（Redis/事务、Spring/事务）都进候选串，
     * 候选串必须带「父分类 / 名」域信息；域感知 LLM 据此选中 Spring 那个，绝不错配到 Redis。
     */
    @Test
    void 用例1_候选带域路径_LLM按域正确匹配不串到Redis() {
        long redisCat = seedCategory("Redis");
        long redisTx = seedKp(redisCat, "事务与lua脚本");
        long springCat = seedCategory("Spring");
        long springTx = seedKp(springCat, "事务失效场景");

        // 域感知 LLM：从候选里挑"Spring"那一行
        llm.rerankResponder = vars -> {
            Long id = pickIdByDomain(String.valueOf(vars.get("candidates")), "Spring");
            return "{\"node_id\": " + id + "}";
        };

        List<Map<String, Object>> enriched = matcher.matchNodes(List.of(knowledgeGroup("Spring 事务为什么会失效")));
        Long matched = ((Number) enriched.get(0).get("matched_node_id")).longValue();

        // fix A：候选串确实带了父分类路径
        assertNotNull(llm.lastCandidates, "应触发 rerank 并捕获候选串");
        assertTrue(llm.lastCandidates.contains("Redis / 事务与lua脚本"),
                "候选串应带 Redis 域路径，实际=\n" + llm.lastCandidates);
        assertTrue(llm.lastCandidates.contains("Spring / 事务失效场景"),
                "候选串应带 Spring 域路径，实际=\n" + llm.lastCandidates);
        // 结果：匹配到 Spring，绝不错配到 Redis
        assertEquals(springTx, matched, "应匹配到 Spring 事务失效");
        assertNotEquals(redisTx, matched, "绝不能跨域错配到 Redis 事务");
    }

    /**
     * 用例 2（fix B）：只有 Redis/事务 一个候选，问的是 Spring 事务。
     * 域感知 LLM 判"都不匹配"返 null → 不应错配 Redis，而应在「未命名知识点」下新建占位叶子。
     */
    @Test
    void 用例2_跨域LLM返null_建占位叶子而非错配() {
        long redisCat = seedCategory("Redis");
        long redisTx = seedKp(redisCat, "事务与lua脚本");

        llm.rerankResponder = vars -> "{\"node_id\": null}";   // 跨域，拒绝

        List<Map<String, Object>> enriched = matcher.matchNodes(List.of(knowledgeGroup("Spring 事务失效场景")));
        Long matched = ((Number) enriched.get(0).get("matched_node_id")).longValue();

        // 候选串带了域（fix A 仍生效）
        assertTrue(llm.lastCandidates.contains("Redis / 事务与lua脚本"),
                "候选串应带 Redis 域路径，实际=\n" + llm.lastCandidates);
        // 没有错配到 Redis
        assertNotEquals(redisTx, matched, "跨域拒绝后不能错配到 Redis");
        // 新建了占位叶子：node_type=knowledge_point、名字=问的知识点、父=「未命名知识点」
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT t.name AS name, t.node_type AS node_type, p.name AS parent_name "
                        + "FROM tree_node t LEFT JOIN tree_node p ON p.id = t.parent_id WHERE t.id = ?", matched);
        assertEquals("Spring 事务失效场景", row.get("name"));
        assertEquals("knowledge_point", row.get("node_type"));
        assertEquals("未命名知识点", row.get("parent_name"), "占位叶子应挂在『未命名知识点』下");
    }

    /**
     * 用例 3（对照/回归）：同域且语义对时，LLM 选中它，正常命中，不新建垃圾节点。
     */
    @Test
    void 用例3_同域命中_正常匹配() {
        long redisCat = seedCategory("Redis");
        long redisPersist = seedKp(redisCat, "持久化 RDB 与 AOF");

        llm.rerankResponder = vars -> {
            Long id = pickIdByDomain(String.valueOf(vars.get("candidates")), "持久化");
            return "{\"node_id\": " + id + "}";
        };

        List<Map<String, Object>> enriched = matcher.matchNodes(List.of(knowledgeGroup("Redis 持久化怎么做")));
        Long matched = ((Number) enriched.get(0).get("matched_node_id")).longValue();

        assertEquals(redisPersist, matched, "同域应正常命中现有知识点");
        assertTrue(llm.lastCandidates.contains("Redis / 持久化 RDB 与 AOF"), "候选串带域路径");
    }
}
