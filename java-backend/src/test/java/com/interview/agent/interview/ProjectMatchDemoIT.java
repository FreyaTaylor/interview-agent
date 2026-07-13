package com.interview.agent.interview;

import com.interview.agent.auth.CurrentUserTestSupport;
import com.interview.agent.infra.llm.EmbeddingProperties;
import com.interview.agent.infra.llm.EmbeddingService;
import com.interview.agent.interview.matcher.InterviewNodeMatcher;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 【DEMO】项目匹配逻辑演示 —— 证明「项目匹配天然无跨域错配」，与知识点匹配（扁平全树召回）不同。
 *
 * <p>核心对照点：知识点匹配是<b>扁平单层</b>——在整棵知识树的所有叶子里按向量找最近，
 * 阈值宽（≤0.5），所以"Spring 事务"能被"Redis 事务"抢走（跨域）。
 *
 * <p>而项目匹配是<b>三级作用域收窄</b>：项目根(精确名/LLM) → 话题(精确名/LLM) → 问题叶子(embedding)，
 * 且问题 embedding 召回被 {@code parent_id = topicId} 锁死在"已匹配到的那个话题"下。
 * 本 DEMO 故意给两个不同项目的问题叶子写<b>完全相同</b>的向量，验证：
 * 匹配"电商系统/缓存"时，绝不会串到"支付系统/事务"下那道向量一模一样的题 —— 结构性隔离。
 *
 * <p>用 @Primary 假 {@link EmbeddingService} 返回固定向量绕开 DashScope；@Transactional 回滚保护真库。
 */
@SpringBootTest
@Import(ProjectMatchDemoIT.FakeEmbeddingConfig.class)
@Transactional
class ProjectMatchDemoIT {

    /** 固定 1024 维向量：所有文本都返回它 → 任何题的向量距离都是 0（"最像"）。 */
    static final String VEC = "[1" + ",0".repeat(1023) + "]";

    @TestConfiguration
    static class FakeEmbeddingConfig {
        @Bean
        @Primary
        EmbeddingService fakeEmbeddingService() {
            return new EmbeddingService(new EmbeddingProperties(null, "text-embedding-v3", 1024)) {
                @Override
                public String embedToLiteral(String text) {
                    return VEC;
                }
            };
        }
    }

    @Autowired
    private InterviewNodeMatcher matcher;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        CurrentUserTestSupport.set(1L);
    }

    @AfterEach
    void tearDown() {
        CurrentUserTestSupport.clear();
    }

    private long seedProjectNode(Long parentId, String name, short level, String type, String embedding) {
        return jdbc.queryForObject(
                "INSERT INTO tree_node (tree_kind, user_id, parent_id, name, level, node_type, sort_order, embedding) "
                        + "VALUES ('project', 1, ?, ?, ?, ?, 0, ?::vector) RETURNING id",
                Long.class, parentId, name, level, type, embedding);
    }

    @Test
    void 项目匹配_三级作用域收窄_不跨项目串题() {
        // 项目 A：电商秒杀系统 / 缓存设计 / 「如何防止缓存击穿」
        long projA = seedProjectNode(null, "电商秒杀系统", (short) 1, "project", null);
        long topicA = seedProjectNode(projA, "缓存设计", (short) 2, "topic", null);
        long qA = seedProjectNode(topicA, "如何防止缓存击穿", (short) 3, "question", VEC);

        // 项目 B：支付结算系统 / 分布式事务 / 「TCC 怎么实现」——注意向量与 qA 完全相同（VEC）
        long projB = seedProjectNode(null, "支付结算系统", (short) 1, "project", null);
        long topicB = seedProjectNode(projB, "分布式事务", (short) 2, "topic", null);
        long qB = seedProjectNode(topicB, "TCC 怎么实现", (short) 3, "question", VEC);

        // 面试分组：明确说是「电商秒杀系统 / 缓存设计」下的一道题（问法不同但语义同）
        Map<String, Object> group = Map.of(
                "type", "project",
                "project_name", "电商秒杀系统",   // 精确名命中 projA
                "topic", "缓存设计",             // 精确名命中 topicA
                "questions", List.of("缓存击穿怎么处理"));

        List<Map<String, Object>> enriched = matcher.matchNodes(List.of(group));
        Object matchedId = enriched.get(0).get("matched_project_id");

        // 断言 1：命中的是电商系统那道题 qA（作用域内 embedding 命中，distance=0 → 累积表述）
        assertEquals(qA, ((Number) matchedId).longValue(),
                "应命中『电商秒杀系统/缓存设计』下的问题叶子 qA");
        // 断言 2：绝不会串到支付系统那道向量一模一样的 qB —— 证明项目匹配无跨域错配
        assertNotEquals(qB, ((Number) matchedId).longValue(),
                "即便 qB 向量与 qA 完全相同，也不能跨项目串题（parent_id=topicId 锁死作用域）");

        // 断言 3：命中后 qA 的 name 累积了新表述（match_or_create_question 命中分支）
        String qaName = jdbc.queryForObject("SELECT name FROM tree_node WHERE id = ?", String.class, qA);
        assertTrue(qaName.contains("如何防止缓存击穿") && qaName.contains("缓存击穿怎么处理"),
                "命中应累积表述，实际=" + qaName);
    }
}
