package com.interview.agent.interview.exp;

import com.interview.agent.auth.CurrentUserTestSupport;
import com.interview.agent.common.LlmInvoker;
import com.interview.agent.infra.llm.EmbeddingProperties;
import com.interview.agent.infra.llm.EmbeddingService;
import com.interview.agent.infra.llm.QwenVisionClient;
import com.interview.agent.interview.exp.dto.InterviewExpNodeView;
import com.interview.agent.interview.exp.dto.InterviewExpParseResult;
import com.interview.agent.interview.exp.service.InterviewExpAdminService;
import com.interview.agent.interview.exp.service.InterviewExpParseService;
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
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 面经解析集成测试（Spec 6b demo A/B/C/D）—— 验证 rewrite 收敛 / 跨批计频 / hash 拦 / embedding 拦。
 *
 * <p>不用 Mockito（Java 25 下 Byte Buddy inline mock 不支持），改用 {@code @TestConfiguration + @Primary} 假 bean：
 * <ul>
 *   <li><b>假 LLM</b>：{@link FakeLlm} 按 Spec.vars 的 text 内容返回预设 JSON（用真实 parser 解析，保持 ParsedItem 私有）。</li>
 *   <li><b>假 Embedding</b>：{@link FakeEmbedding} 把文本归一为「语义 key」（去空白/标点/emoji，小写）→ 每个不同 key
 *       映射一个 one-hot 向量，同 key 距离 0、异 key 距离 1；于是 rewrite 相同的问题互相命中、同文加 emoji 整篇被拦。</li>
 * </ul>
 * 隔离测试用户 + {@code @Transactional} 回滚。
 */
@SpringBootTest
@Import(InterviewExpParseIT.FakeBeans.class)
@Transactional
class InterviewExpParseIT {

    static final long USER = 990071L;

    // ---- demo 文本 ----
    static final String DEMO_A = """
            今天面了字节后端，记录一下～
            1. 讲讲 MySQL 索引什么时候会失效？
            2. redis 的持久化方式有哪些，各自优缺点
            3. 聊聊你对 mysql 索引失效的理解
            4. HashMap 底层是怎么实现的？
            5. synchronized 和 ReentrantLock 区别
            """;
    static final String DEMO_B = """
            美团二面面经
            - MySQL 索引失效的场景有哪些？
            - 说下 TCP 三次握手
            """;
    static final String DEMO_D = DEMO_A.replace("记录一下～", "记录一下😊～");

    static final String JSON_A = """
            [{"domain":"MySQL","question":"MySQL 索引失效的常见场景有哪些？"},
             {"domain":"Redis","question":"Redis 的持久化方式有哪些？各自优缺点是什么？"},
             {"domain":"MySQL","question":"MySQL 索引失效的常见场景有哪些？"},
             {"domain":"Java","question":"HashMap 的底层实现原理是什么？"},
             {"domain":"Java","question":"synchronized 和 ReentrantLock 的区别是什么？"}]
            """;
    static final String JSON_B = """
            [{"domain":"MySQL","question":"MySQL 索引失效的常见场景有哪些？"},
             {"domain":"计算机网络","question":"TCP 三次握手的过程是怎样的？"}]
            """;

    @TestConfiguration
    static class FakeBeans {
        @Bean @Primary
        EmbeddingService fakeEmbeddingService() {
            return new FakeEmbedding();
        }

        @Bean @Primary
        LlmInvoker fakeLlm() {
            return new FakeLlm();
        }

        @Bean @Primary
        QwenVisionClient fakeVision() {
            return new FakeVision();
        }
    }

    /** 假 视觉模型：OCR 固定返回 Demo A 文本（验证 from-image → OCR → 复用 parseFromText 链路）。 */
    static class FakeVision extends QwenVisionClient {
        FakeVision() {
            super(new EmbeddingProperties(null, "text-embedding-v3", 1024));
        }

        @Override
        public String parseImage(String imageBase64, String mediaType, String prompt, double temperature) {
            return DEMO_A;
        }
    }

    /** 假 Embedding：语义 key → one-hot 向量（同 key 距离 0，异 key 距离 1）。 */
    static class FakeEmbedding extends EmbeddingService {
        private final ConcurrentHashMap<String, Integer> reg = new ConcurrentHashMap<>();
        private final AtomicInteger seq = new AtomicInteger();

        FakeEmbedding() {
            super(new EmbeddingProperties(null, "text-embedding-v3", 1024));
        }

        @Override
        public String embedToLiteral(String text) {
            String key = semanticKey(text);
            int idx = reg.computeIfAbsent(key, k -> seq.getAndIncrement()) % 1024;
            return oneHotLiteral(idx);
        }
    }

    /** 假 LLM：按 text 内容返回预设 JSON，交真实 parser 解析。 */
    static class FakeLlm extends LlmInvoker {
        FakeLlm() {
            super(null, null);
        }

        @Override
        public <T> Optional<T> invoke(Spec spec, ResponseParser<T> parser) {
            if (!PromptKeys.INTERVIEW_EXP_PARSE.equals(spec.promptKey())) {
                return Optional.empty();
            }
            String text = String.valueOf(spec.vars().get("text"));
            String json = text.contains("字节") ? JSON_A : text.contains("美团") ? JSON_B : "[]";
            try {
                return Optional.of(parser.parse(json));
            } catch (Exception e) {
                return Optional.empty();
            }
        }
    }

    @Autowired
    private InterviewExpParseService parseService;
    @Autowired
    private InterviewExpAdminService adminService;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        CurrentUserTestSupport.set(USER);
    }

    @AfterEach
    void tearDown() {
        CurrentUserTestSupport.clear();
    }

    @Test
    void demos_A_B_C_D() {
        // Demo A：基础解析 + 同批去重 + rewrite 收敛
        InterviewExpParseResult a = parseService.parseFromText(DEMO_A);
        assertFalse(a.duplicateSource());
        assertEquals(5, a.totalParsed());
        assertEquals(4, a.newQuestions(), "第1、3条 rewrite 相同 → 收敛为 4 题");
        assertEquals(1, a.matchedQuestions());
        assertEquals(3, a.newDomains(), "MySQL/Redis/Java");

        // Demo B：新来源 + 跨批计频 + 新增题
        InterviewExpParseResult b = parseService.parseFromText(DEMO_B);
        assertFalse(b.duplicateSource());
        assertEquals(2, b.totalParsed());
        assertEquals(1, b.newQuestions(), "TCP 新题");
        assertEquals(1, b.matchedQuestions(), "MySQL 索引失效命中 A");
        assertEquals(1, b.newDomains(), "计算机网络");

        // Demo C：同篇原样再传 → hash 精确拦
        assertTrue(parseService.parseFromText(DEMO_A).duplicateSource(), "hash 命中，整篇拒");

        // Demo D：同篇加 emoji → 整篇 embedding 模糊拦
        assertTrue(parseService.parseFromText(DEMO_D).duplicateSource(), "整篇 embedding 命中，整篇拒");

        // ---- 整体断言 ----
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM interview_exp_source WHERE user_id = ?", Integer.class, USER),
                "A、B 落库；C、D 被拦");

        List<InterviewExpNodeView> all = adminService.listAll();
        assertEquals(4, all.stream().filter(n -> "domain".equals(n.nodeType())).count(), "4 个域");
        assertEquals(5, all.stream().filter(n -> "question".equals(n.nodeType())).count(), "5 个问题");
        int idxFreq = all.stream()
                .filter(n -> "question".equals(n.nodeType()) && n.name().contains("索引失效"))
                .findFirst().orElseThrow().frequency();
        assertEquals(2, idxFreq, "MySQL 索引失效：A、B 各一 → 频率 2");
    }

    @Test
    void demo_E_image() {
        // Demo E：图片 OCR 链路 —— 假 vision 识别出 Demo A 文本 → 后续与 from-text 同流水
        InterviewExpParseResult r = parseService.parseFromImage("ZmFrZQ==", "image/png");
        assertFalse(r.duplicateSource());
        assertEquals(5, r.totalParsed());
        assertEquals(4, r.newQuestions(), "同 A：rewrite 收敛为 4 题");
        assertEquals(3, r.newDomains(), "MySQL/Redis/Java");

        List<InterviewExpNodeView> all = adminService.listAll();
        assertEquals(3, all.stream().filter(n -> "domain".equals(n.nodeType())).count());
        assertEquals(4, all.stream().filter(n -> "question".equals(n.nodeType())).count());
    }

    // ---- stub helpers ----

    private static String semanticKey(String text) {
        return text == null ? "" : text.replaceAll("[^\\p{IsHan}a-zA-Z0-9]", "").toLowerCase();
    }

    private static String oneHotLiteral(int idx) {
        StringBuilder sb = new StringBuilder(2100).append('[');
        for (int i = 0; i < 1024; i++) {
            sb.append(i == idx ? '1' : '0');
            if (i < 1023) {
                sb.append(',');
            }
        }
        return sb.append(']').toString();
    }
}
