package com.interview.agent.interview;

import com.interview.agent.auth.CurrentUserTestSupport;
import com.interview.agent.common.BizException;
import com.interview.agent.interview.service.InterviewBasicService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 面试记录用户隔离集成测试（Spec 2026-07-15 §3.5）。
 *
 * <p>验证 A 不能读/改/删 B 的 {@code interview_record}——尤其 {@code deleteById}（曾可删他人记录）。
 * {@code @Transactional} 回滚保护，不调 LLM/Embedding。
 */
@SpringBootTest
@Transactional
class InterviewRecordIsolationIT {

    static final long USER_A = 990001L;
    static final long USER_B = 990002L;

    @Autowired
    private InterviewBasicService service;
    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void tearDown() {
        CurrentUserTestSupport.clear();
    }

    private long seedRecord(long userId, String company) {
        return jdbc.queryForObject(
                "INSERT INTO interview_record (user_id, raw_text, company, position) "
                        + "VALUES (?, 'seed', ?, '后端') RETURNING id",
                Long.class, userId, company);
    }

    private boolean recordExists(long id) {
        Integer c = jdbc.queryForObject("SELECT COUNT(*) FROM interview_record WHERE id = ?", Integer.class, id);
        return c != null && c > 0;
    }

    @Test
    void historyDetail_otherUsersRecord_denied() {
        long bRec = seedRecord(USER_B, "B公司");
        CurrentUserTestSupport.set(USER_A);
        assertThrows(BizException.class, () -> service.historyDetail(bRec));
    }

    @Test
    void delete_otherUsersRecord_denied_andStillExists() {
        long bRec = seedRecord(USER_B, "B公司");
        CurrentUserTestSupport.set(USER_A);
        assertThrows(BizException.class, () -> service.historyDelete(bRec));
        assertTrue(recordExists(bRec), "B 的面试记录不应被 A 删除");
    }

    @Test
    void updateMeta_otherUsersRecord_denied_andUnchanged() {
        long bRec = seedRecord(USER_B, "B公司");
        CurrentUserTestSupport.set(USER_A);
        assertThrows(BizException.class, () -> service.updateMeta(bRec, "被A改", "被A改"));
        String company = jdbc.queryForObject("SELECT company FROM interview_record WHERE id = ?", String.class, bRec);
        assertEquals("B公司", company);
    }

    @Test
    void delete_ownRecord_succeeds() {
        long aRec = seedRecord(USER_A, "A公司");
        CurrentUserTestSupport.set(USER_A);
        service.historyDelete(aRec);
        assertTrue(!recordExists(aRec), "A 应能删除自己的记录");
    }
}
