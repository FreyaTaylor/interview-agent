package com.interview.agent.interview.exp;

import com.interview.agent.admin.dto.BatchSortReq;
import com.interview.agent.admin.dto.DeleteNodeReq;
import com.interview.agent.auth.CurrentUserTestSupport;
import com.interview.agent.interview.exp.dto.CreateInterviewExpNodeReq;
import com.interview.agent.interview.exp.dto.InterviewExpNodeView;
import com.interview.agent.interview.exp.dto.UpdateInterviewExpNodeReq;
import com.interview.agent.interview.exp.service.InterviewExpAdminService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 面经树 Admin CRUD 冒烟测试（Spec 第①片）—— 验证 V70 迁移 + 节点增删改查 + 频率列。
 *
 * <p>隔离测试用户绕开真实数据；{@code @Transactional} 回滚。不调 LLM/DashScope（域名向量化失败降级 null，不影响 CRUD）。
 *
 * <p>注意 MyBatis 一级缓存：同一 SqlSession（测试单事务）内相同查询会命中缓存，
 * 且经 JdbcTemplate 的直插不会清 MyBatis 缓存。故本测试在<b>首次 listAll 之前</b>就造好 link。
 * 生产每请求新 session 无此问题。
 */
@SpringBootTest
@Transactional
class InterviewExpAdminIT {

    static final long USER = 990070L;

    @Autowired
    private InterviewExpAdminService service;
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
    void crud_and_frequency() {
        // 1. 建域（level1 → domain）
        Map<String, Object> domain = service.create(new CreateInterviewExpNodeReq(null, "MySQL"));
        long domainId = ((Number) domain.get("id")).longValue();
        assertEquals(1, ((Number) domain.get("level")).intValue());

        // 2. 域下建两个问题（level2 → question）：q1 关联 2 来源，q2 无来源
        long q1 = ((Number) service.create(new CreateInterviewExpNodeReq(domainId, "MySQL 索引失效的常见场景有哪些？")).get("id")).longValue();
        long q2 = ((Number) service.create(new CreateInterviewExpNodeReq(domainId, "MySQL 事务隔离级别有哪些？")).get("id")).longValue();

        // 3. 造 link（在首次 listAll 之前，避免 MyBatis 一级缓存读到旧频率）
        seedLink(q1, seedSource("hash-a"));
        seedLink(q1, seedSource("hash-b"));

        // 4. 首次 list：3 节点，类型 + 频率正确
        List<InterviewExpNodeView> all = service.listAll();
        assertEquals(3, all.size());
        assertEquals("domain", view(all, domainId).nodeType());
        assertEquals("question", view(all, q1).nodeType());
        assertEquals(2, view(all, q1).frequency(), "q1 两条不同来源 → 频率 2");
        assertEquals(0, view(all, q2).frequency(), "q2 无关联来源 → 频率 0");

        // 5. 改名（经 MyBatis 写 → 清一级缓存）
        service.update(new UpdateInterviewExpNodeReq(q1, "MySQL 索引什么情况下会失效？", null, null, null));
        assertEquals("MySQL 索引什么情况下会失效？",
                jdbc.queryForObject("SELECT name FROM tree_node WHERE id = ?", String.class, q1));

        // 6. batch-sort
        assertEquals(1, ((Number) service.batchSort(new BatchSortReq(List.of(new BatchSortReq.Item(q1, 5)))).get("updated")).intValue());

        // 7. 删域 → 级联删问题 + link
        service.delete(new DeleteNodeReq(domainId));
        assertTrue(service.listAll().isEmpty());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM question_source_link WHERE question_node_id = ?", Integer.class, q1),
                "问题删除 → link 级联清空");
    }

    private static InterviewExpNodeView view(List<InterviewExpNodeView> all, long id) {
        return all.stream().filter(n -> n.id() == id).findFirst().orElseThrow();
    }

    private long seedSource(String hash) {
        return jdbc.queryForObject(
                "INSERT INTO interview_exp_source (user_id, raw_text, text_hash) VALUES (?, 'seed', ?) RETURNING id",
                Long.class, USER, hash + "-" + USER);
    }

    private void seedLink(long questionNodeId, long sourceId) {
        jdbc.update("INSERT INTO question_source_link (question_node_id, source_id) VALUES (?, ?)",
                questionNodeId, sourceId);
    }
}
