package com.interview.agent.interview.exp.study;

import com.interview.agent.auth.CurrentUserTestSupport;
import com.interview.agent.common.BizException;
import com.interview.agent.common.LlmInvoker;
import com.interview.agent.interview.exp.study.dto.ExpContentRequest;
import com.interview.agent.interview.exp.study.dto.ExpQuestionView;
import com.interview.agent.interview.exp.study.service.ExpStudyService;
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

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「看看面经」学习页集成测试（Spec 6b D1–D5）—— 懒生成落库 / 二次读库不调 LLM / regenerate / 看过次数 +1 / 用户隔离。
 *
 * <p>Java 25 下不用 Mockito，改 {@code @TestConfiguration + @Bean @Primary} 假 {@link LlmInvoker}：
 * 按 promptKey 返回预设 rubric/讲解，并计数调用次数（验证二次读库不触发 LLM）。隔离测试用户 + 回滚。
 */
@SpringBootTest
@Import(InterviewExpStudyIT.FakeBeans.class)
@Transactional
class InterviewExpStudyIT {

    static final long USER = 990073L;
    static final long OTHER_USER = 990074L;

    static final String RUBRIC_JSON = """
            {"rubric":[{"key_point":"索引失效","hit_rule":"提到隐式转换或函数即命中","score":100}],
             "recommended_answer":["**索引失效**：在 `WHERE` 上用函数或发生隐式类型转换会使索引失效。"]}
            """;
    static final String CONTENT_MD = "### 索引失效\nMySQL 在 `WHERE` 列上使用函数或隐式类型转换时，无法走 B+ 树索引。";

    @TestConfiguration
    static class FakeBeans {
        @Bean @Primary
        LlmInvoker fakeLlm() {
            return new FakeLlm();
        }
    }

    /** 假 LLM：按 promptKey 返回预设内容并计数。 */
    static class FakeLlm extends LlmInvoker {
        final AtomicInteger calls = new AtomicInteger();

        FakeLlm() {
            super(null, null);
        }

        @Override
        public <T> Optional<T> invoke(Spec spec, ResponseParser<T> parser) {
            try {
                if (PromptKeys.INTERVIEW_EXP_RUBRIC_GEN.equals(spec.promptKey())) {
                    calls.incrementAndGet();
                    return Optional.of(parser.parse(RUBRIC_JSON));
                }
                if (PromptKeys.INTERVIEW_EXP_QUESTION_CONTENT.equals(spec.promptKey())) {
                    calls.incrementAndGet();
                    return Optional.of(parser.parse(CONTENT_MD));
                }
            } catch (Exception e) {
                return Optional.empty();
            }
            return Optional.empty();
        }
    }

    @Autowired
    private ExpStudyService service;
    @Autowired
    private FakeLlm fakeLlm;
    @Autowired
    private JdbcTemplate jdbc;

    private long questionId;

    @BeforeEach
    void setUp() {
        CurrentUserTestSupport.set(USER);
        fakeLlm.calls.set(0);
        long domainId = jdbc.queryForObject(
                "INSERT INTO tree_node (user_id, tree_kind, node_type, name, level, sort_order) "
                        + "VALUES (?, 'interview_exp', 'domain', 'MySQL', 1, 0) RETURNING id",
                Long.class, USER);
        questionId = jdbc.queryForObject(
                "INSERT INTO tree_node (user_id, tree_kind, parent_id, node_type, name, level, sort_order) "
                        + "VALUES (?, 'interview_exp', ?, 'question', 'MySQL 索引失效的常见场景有哪些？', 2, 0) RETURNING id",
                Long.class, USER, domainId);
    }

    @AfterEach
    void tearDown() {
        CurrentUserTestSupport.clear();
    }

    @Test
    void lazy_generation_and_readback_and_regenerate() {
        // D1 首次生成（懒生成落库）
        ExpQuestionView v1 = service.resolveContent(new ExpContentRequest(questionId, "fetch"));
        assertTrue(v1.generated(), "首次应生成");
        assertFalse(v1.bodyMd() == null || v1.bodyMd().isBlank(), "讲解非空");
        assertEquals(1, v1.rubric().size(), "rubric 1 点");
        assertEquals(1, v1.recommendedAnswer().size(), "推荐答案 1 条");
        assertEquals("ready", jdbc.queryForObject(
                "SELECT content_status FROM interview_exp_question_detail WHERE node_id = ?", String.class, questionId));
        assertEquals(2, fakeLlm.calls.get(), "首次调 rubric + 讲解 = 2 次");

        // D2 二次读库（不再调 LLM）
        ExpQuestionView v2 = service.resolveContent(new ExpContentRequest(questionId, "fetch"));
        assertFalse(v2.generated(), "二次应读库");
        assertEquals(CONTENT_MD, v2.bodyMd());
        assertEquals(2, fakeLlm.calls.get(), "二次 fetch 不再调 LLM");

        // D3 regenerate（强制重生）
        ExpQuestionView v3 = service.resolveContent(new ExpContentRequest(questionId, "regenerate"));
        assertTrue(v3.generated(), "regenerate 应重生");
        assertEquals(4, fakeLlm.calls.get(), "regenerate 再调 rubric + 讲解 = 累计 4 次");
    }

    @Test
    void view_count_increments() {
        // 首次未看过为 0；木鱼敲 3 下 → 累计 1/2/3，落库到 interview_exp_question_detail
        assertEquals(1, service.incrementView(questionId));
        assertEquals(2, service.incrementView(questionId));
        assertEquals(3, service.incrementView(questionId));
        assertEquals(3, jdbc.queryForObject(
                "SELECT view_count FROM interview_exp_question_detail WHERE node_id = ?", Integer.class, questionId));
    }

    @Test
    void toggle_skip_flips() {
        // 默认未标记；🚫 点两下 true→false，落库到 interview_exp_question_detail
        assertTrue(service.toggleSkip(questionId), "首次反转为 true");
        assertTrue(jdbc.queryForObject(
                "SELECT skipped FROM interview_exp_question_detail WHERE node_id = ?", Boolean.class, questionId));
        assertFalse(service.toggleSkip(questionId), "再点反转回 false");
        assertFalse(jdbc.queryForObject(
                "SELECT skipped FROM interview_exp_question_detail WHERE node_id = ?", Boolean.class, questionId));
    }

    @Test
    void user_isolation() {
        // 另一用户看不到本人面经问题 → 40400
        CurrentUserTestSupport.set(OTHER_USER);
        assertThrows(BizException.class,
                () -> service.resolveContent(new ExpContentRequest(questionId, "fetch")));
        assertThrows(BizException.class, () -> service.incrementView(questionId));
        assertThrows(BizException.class, () -> service.toggleSkip(questionId));
    }
}
