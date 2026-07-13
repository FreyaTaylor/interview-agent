package com.interview.agent.interview;

import com.interview.agent.interview.entity.InterviewQuestionKpLink;
import com.interview.agent.interview.mapper.InterviewQuestionKpLinkMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * P1 特征/单元测试（DB 集成）：面试真题 ↔ 知识点 关联表的 upsert 幂等 + 双向查询。
 *
 * <p>@Transactional 回滚保护本地真实库；纯 DB，无需 LLM。
 */
@SpringBootTest
@Transactional
class InterviewQuestionKpLinkIT {

    @Autowired
    private InterviewQuestionKpLinkMapper linkMapper;
    @Autowired
    private JdbcTemplate jdbc;

    private long seedInterviewQuestion() {
        Long recordId = jdbc.queryForObject(
                "INSERT INTO interview_record (raw_text) VALUES ('测试原文') RETURNING id", Long.class);
        return jdbc.queryForObject(
                "INSERT INTO interview_knowledge_question (interview_record_id, tag) VALUES (?, 'x') RETURNING id",
                Long.class, recordId);
    }

    private long seedKnowledgePoint(String name) {
        return jdbc.queryForObject(
                "INSERT INTO tree_node (tree_kind, node_type, name, level, sort_order) "
                        + "VALUES ('knowledge', 'knowledge_point', ?, 2, 0) RETURNING id", Long.class, name);
    }

    @Test
    void upsert_isIdempotent_andUpdatesOnConflict() {
        long ikqId = seedInterviewQuestion();
        long kpId = seedKnowledgePoint("测试知识点");

        // 同一 (真题, 知识点) 连续 upsert 两次
        linkMapper.upsert(1L, ikqId, kpId, "测试知识点", "recall", 0.80f);
        linkMapper.upsert(1L, ikqId, kpId, "测试知识点", "recall", 0.95f);

        // 只应留一条（幂等），且相似度被更新为最后一次
        List<InterviewQuestionKpLink> byKp = linkMapper.findByKnowledgePoint(kpId);
        assertEquals(1, byKp.size(), "同一对只应有一条关联");
        assertEquals(0.95f, byKp.get(0).similarity(), 0.0001, "相似度应更新为最后一次");
        assertEquals("测试知识点", byKp.get(0).knowledgePointName());

        // 反向查询也应命中
        List<InterviewQuestionKpLink> byQ = linkMapper.findByInterviewQuestion(ikqId);
        assertEquals(1, byQ.size(), "反向查询应命中");
        assertEquals(kpId, byQ.get(0).knowledgePointId());
    }

    @Test
    void oneQuestion_canLinkMultipleKnowledgePoints() {
        long ikqId = seedInterviewQuestion();
        long kp1 = seedKnowledgePoint("知识点甲");
        long kp2 = seedKnowledgePoint("知识点乙");

        linkMapper.upsert(1L, ikqId, kp1, "知识点甲", "recall", 0.9f);
        linkMapper.upsert(1L, ikqId, kp2, "知识点乙", "recall", 0.7f);

        // N:N：一道真题关联多个知识点
        assertEquals(2, linkMapper.findByInterviewQuestion(ikqId).size(), "一道真题应能关联多个知识点");
    }
}
