package com.interview.agent.interview;

import com.interview.agent.auth.CurrentUserTestSupport;
import com.interview.agent.infra.llm.EmbeddingProperties;
import com.interview.agent.infra.llm.EmbeddingService;
import com.interview.agent.interview.entity.InterviewQuestionKpLink;
import com.interview.agent.interview.mapper.InterviewQuestionKpLinkMapper;
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
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2 特征测试（DB 集成）：面试真题 → 语义召回相关知识点 → 写 interview_question_kp_link 关联快照。
 *
 * <p>用 @Primary 假 {@link EmbeddingService} 返回固定向量（绕开 DashScope + JDK25 mock），
 * 并给 seed 的知识点写同一向量（距离 0 → 必排最前），使召回确定；@Transactional 回滚保护真库。
 */
@SpringBootTest
@Import(LinkRecallIT.FakeEmbeddingConfig.class)
@Transactional
class LinkRecallIT {

    /** 固定 1024 维向量字面量：[1,0,0,...,0]。 */
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
    private InterviewQuestionKpLinkMapper linkMapper;
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

    private long seedKpWithEmbedding(String name) {
        return jdbc.queryForObject(
                "INSERT INTO tree_node (tree_kind, node_type, name, level, sort_order, embedding) "
                        + "VALUES ('knowledge', 'knowledge_point', ?, 2, 0, ?::vector) RETURNING id",
                Long.class, name, VEC);
    }

    private long seedInterviewQuestion() {
        Long recordId = jdbc.queryForObject(
                "INSERT INTO interview_record (raw_text) VALUES ('测试原文') RETURNING id", Long.class);
        return jdbc.queryForObject(
                "INSERT INTO interview_knowledge_question (interview_record_id, tag) VALUES (?, 'x') RETURNING id",
                Long.class, recordId);
    }

    @Test
    void linkRelatedKnowledge_writesRecalledSnapshot() {
        long kp1 = seedKpWithEmbedding("知识点甲");
        long kp2 = seedKpWithEmbedding("知识点乙");
        long ikqId = seedInterviewQuestion();

        matcher.linkRelatedKnowledge(ikqId, "虚拟线程与 synchronized");

        List<InterviewQuestionKpLink> links = linkMapper.findByInterviewQuestion(ikqId, 1L);
        Set<Long> linkedKpIds = links.stream()
                .map(InterviewQuestionKpLink::knowledgePointId).collect(Collectors.toSet());

        assertTrue(linkedKpIds.contains(kp1) && linkedKpIds.contains(kp2),
                "两个 seed 知识点都应被召回并写入关联，实际=" + linkedKpIds);
        for (InterviewQuestionKpLink l : links) {
            assertEquals("recall", l.source(), "来源应为 recall");
            assertTrue(l.similarity() != null && l.similarity() > 0.9f,
                    "距离 0 的候选相似度应≈1，实际=" + l.similarity());
            assertTrue(l.knowledgePointName() != null && !l.knowledgePointName().isBlank(),
                    "应有知识点名快照");
        }
    }
}
