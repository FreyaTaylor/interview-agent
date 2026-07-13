package com.interview.agent.interview;

import com.interview.agent.auth.CurrentUserTestSupport;
import com.interview.agent.interview.dto.RelatedInterviewQuestion;
import com.interview.agent.interview.mapper.InterviewQuestionKpLinkMapper;
import com.interview.agent.interview.service.InterviewRelatedQuestionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P3 特征测试（DB 集成）：知识点 → 相关面试真题 只读查询（JOIN 关联表 + 解析题干）。
 */
@SpringBootTest
@Transactional
class RelatedQuestionQueryIT {

    @Autowired
    private InterviewRelatedQuestionService relatedQuestionService;
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

    @Test
    void byKnowledgePoint_returnsLinkedQuestionsWithParsedText() {
        Long recordId = jdbc.queryForObject(
                "INSERT INTO interview_record (raw_text, company, position) VALUES ('原文', '测试公司', '后端') RETURNING id",
                Long.class);
        Long ikqId = jdbc.queryForObject(
                "INSERT INTO interview_knowledge_question (interview_record_id, tag, questions) "
                        + "VALUES (?, 'Java 并发', '[\"什么是虚拟线程？\",\"synchronized 会 pin 吗？\"]'::jsonb) RETURNING id",
                Long.class, recordId);
        Long kpId = jdbc.queryForObject(
                "INSERT INTO tree_node (tree_kind, node_type, name, level, sort_order) "
                        + "VALUES ('knowledge', 'knowledge_point', '虚拟线程', 2, 0) RETURNING id", Long.class);

        linkMapper.upsert(1L, ikqId, kpId, "虚拟线程", "recall", 0.9f);

        List<RelatedInterviewQuestion> related = relatedQuestionService.byKnowledgePoint(kpId);

        assertEquals(1, related.size(), "应查到一道相关真题");
        RelatedInterviewQuestion q = related.get(0);
        assertEquals(List.of("什么是虚拟线程？", "synchronized 会 pin 吗？"), q.questions(), "题干应被解析成数组");
        assertEquals("测试公司", q.company(), "应带面试记录的公司");
        assertEquals("Java 并发", q.tag());
        assertTrue(q.similarity() != null && q.similarity() > 0.8f, "应带相似度");
        assertEquals(recordId, q.interviewRecordId(), "应可溯源到面试记录");
    }

    @Test
    void byKnowledgePoint_returnsEmptyWhenNoLink() {
        Long kpId = jdbc.queryForObject(
                "INSERT INTO tree_node (tree_kind, node_type, name, level, sort_order) "
                        + "VALUES ('knowledge', 'knowledge_point', '无关联知识点', 2, 0) RETURNING id", Long.class);
        assertTrue(relatedQuestionService.byKnowledgePoint(kpId).isEmpty(), "无关联应返回空");
    }
}
