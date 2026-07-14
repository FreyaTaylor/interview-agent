package com.interview.agent.interview;

import com.interview.agent.auth.CurrentUserTestSupport;
import com.interview.agent.common.BizException;
import com.interview.agent.interview.dto.InterviewAdminQuestionItem;
import com.interview.agent.interview.dto.InterviewAdminRecordGroup;
import com.interview.agent.interview.dto.InterviewAdminTopicGroup;
import com.interview.agent.interview.service.InterviewAdminQuestionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 管理页「面试真题」三层视图 读+写 集成测试（Spec 2026-07-14 §9）。
 *
 * <p>隔离用户 {@link #USER} 造数据绕开真库；{@code @Transactional} 回滚保护。不调 LLM/Embedding。
 * 覆盖：三层归并 + D1 活取主题 + D2 拆 + topicEditable；改文本(jsonb_set/content)、改主题(拒已关联)、
 * 删除(删元素/删空转删行+kp_link 级联)。
 */
@SpringBootTest
@Transactional
class InterviewAdminQuestionsIT {

    static final long USER = 770001L;

    @Autowired
    private InterviewAdminQuestionService service;
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

    // ===== seed helpers =====

    private long seedRecord(String company, String position) {
        return jdbc.queryForObject(
                "INSERT INTO interview_record (user_id, raw_text, company, position) VALUES (?, 'seed', ?, ?) RETURNING id",
                Long.class, USER, company, position);
    }

    private long seedKp(String name) {
        return jdbc.queryForObject(
                "INSERT INTO tree_node (tree_kind, user_id, parent_id, name, level, node_type, interview_weight, sort_order) "
                        + "VALUES ('knowledge', ?, NULL, ?, 1, 'knowledge_point', 3, 0) RETURNING id",
                Long.class, USER, name);
    }

    private long seedKnowledge(long recordId, String tag, String questionsJson) {
        return jdbc.queryForObject(
                "INSERT INTO interview_knowledge_question (interview_record_id, tag, questions) VALUES (?, ?, ?::jsonb) RETURNING id",
                Long.class, recordId, tag, questionsJson);
    }

    private void seedLink(long ikqId, long kpId, String snapshotName) {
        jdbc.update(
                "INSERT INTO interview_question_kp_link (user_id, interview_knowledge_question_id, knowledge_point_id, knowledge_point_name, source, similarity) "
                        + "VALUES (?, ?, ?, ?, 'recall', 0.9)",
                USER, ikqId, kpId, snapshotName);
    }

    private long seedProject(long recordId, String projectName, String questionsJson) {
        return jdbc.queryForObject(
                "INSERT INTO interview_project_question (interview_record_id, project_name, questions) VALUES (?, ?, ?::jsonb) RETURNING id",
                Long.class, recordId, projectName, questionsJson);
    }

    private long seedOther(long recordId, String content, String tag) {
        return jdbc.queryForObject(
                "INSERT INTO interview_other_question (interview_record_id, content, tag) VALUES (?, ?, ?) RETURNING id",
                Long.class, recordId, content, tag);
    }

    private InterviewAdminTopicGroup topic(String name) {
        return service.listAllQuestions().stream()
                .flatMap(r -> r.topics().stream())
                .filter(t -> name.equals(t.topic()))
                .findFirst().orElse(null);
    }

    // ===== 9.2 读 =====

    @Test
    void listAll_nested_threeTypes_liveTopic() {
        long rec = seedRecord("字节", "后端");
        long kpId = seedKp("Redis 持久化");                    // 活知识点名
        long ikq = seedKnowledge(rec, "MySQL", "[\"q1\",\"q2\"]");
        seedLink(ikq, kpId, "旧快照名");                        // 快照名与活名不同
        seedProject(rec, "订单系统", "[\"p1\"]");
        seedOther(rec, "LRU", "算法");

        List<InterviewAdminRecordGroup> view = service.listAllQuestions();
        assertEquals(1, view.size());
        InterviewAdminRecordGroup g = view.get(0);
        assertEquals(rec, g.recordId());
        assertEquals("字节", g.company());

        // D1：knowledge 主题=活知识点名（非快照、非 tag）
        InterviewAdminTopicGroup kt = topic("Redis 持久化");
        assertTrue(kt != null, "应有活知识点名主题");
        // D2：questions 数组展开为 2 条 QItem
        assertEquals(2, kt.questions().size());
        assertEquals(List.of(0, 1), kt.questions().stream().map(InterviewAdminQuestionItem::idx).toList());
        // topicEditable：已关联 → false
        assertTrue(kt.questions().stream().noneMatch(InterviewAdminQuestionItem::topicEditable));

        // project / other 主题可编辑
        assertTrue(topic("订单系统").questions().get(0).topicEditable());
        assertTrue(topic("算法").questions().get(0).topicEditable());
    }

    @Test
    void knowledge_noLink_fallsBackToTag() {
        long rec = seedRecord("A", "B");
        seedKnowledge(rec, "并发编程", "[\"q\"]");   // 无 link
        InterviewAdminTopicGroup t = topic("并发编程");   // 主题=tag
        assertTrue(t != null && t.questions().get(0).topicEditable());
    }

    @Test
    void emptyUser_returnsEmpty() {
        assertTrue(service.listAllQuestions().isEmpty());
    }

    // ===== 9.3 写 =====

    @Test
    void updateText_knowledge_setsJsonbElement() {
        long rec = seedRecord("A", "B");
        long ikq = seedKnowledge(rec, "t", "[\"old0\",\"old1\"]");
        service.updateText("knowledge", ikq, 1, "new1");
        assertEquals("old0", jsonElem("interview_knowledge_question", ikq, 0));
        assertEquals("new1", jsonElem("interview_knowledge_question", ikq, 1));
    }

    @Test
    void updateText_other_setsContent() {
        long rec = seedRecord("A", "B");
        long o = seedOther(rec, "old", "算法");
        service.updateText("other", o, 0, "new");
        assertEquals("new", jdbc.queryForObject(
                "SELECT content FROM interview_other_question WHERE id=?", String.class, o));
    }

    @Test
    void updateText_idxOutOfRange_rejected() {
        long rec = seedRecord("A", "B");
        long ikq = seedKnowledge(rec, "t", "[\"only\"]");
        assertThrows(BizException.class, () -> service.updateText("knowledge", ikq, 5, "x"));
        assertEquals("only", jsonElem("interview_knowledge_question", ikq, 0));
    }

    @Test
    void updateTopic_project_setsProjectName() {
        long rec = seedRecord("A", "B");
        long p = seedProject(rec, "旧项目", "[\"q\"]");
        service.updateTopic("project", p, "新项目");
        assertEquals("新项目", jdbc.queryForObject(
                "SELECT project_name FROM interview_project_question WHERE id=?", String.class, p));
    }

    @Test
    void updateTopic_other_setsTag() {
        long rec = seedRecord("A", "B");
        long o = seedOther(rec, "c", "旧tag");
        service.updateTopic("other", o, "新tag");
        assertEquals("新tag", jdbc.queryForObject(
                "SELECT tag FROM interview_other_question WHERE id=?", String.class, o));
    }

    @Test
    void updateTopic_knowledgeNoLink_setsTag() {
        long rec = seedRecord("A", "B");
        long ikq = seedKnowledge(rec, "旧tag", "[\"q\"]");
        service.updateTopic("knowledge", ikq, "新tag");
        assertEquals("新tag", jdbc.queryForObject(
                "SELECT tag FROM interview_knowledge_question WHERE id=?", String.class, ikq));
    }

    @Test
    void updateTopic_knowledgeWithLink_rejected() {
        long rec = seedRecord("A", "B");
        long kpId = seedKp("活名");
        long ikq = seedKnowledge(rec, "原tag", "[\"q\"]");
        seedLink(ikq, kpId, "快照");
        assertThrows(BizException.class, () -> service.updateTopic("knowledge", ikq, "改不动"));
        assertEquals("原tag", jdbc.queryForObject(
                "SELECT tag FROM interview_knowledge_question WHERE id=?", String.class, ikq));
    }

    @Test
    void delete_knowledge_removesElement() {
        long rec = seedRecord("A", "B");
        long ikq = seedKnowledge(rec, "t", "[\"a\",\"b\"]");
        service.deleteQuestion("knowledge", ikq, 0);
        assertEquals(1, jdbc.queryForObject(
                "SELECT jsonb_array_length(questions) FROM interview_knowledge_question WHERE id=?", Integer.class, ikq));
        assertEquals("b", jsonElem("interview_knowledge_question", ikq, 0));
    }

    @Test
    void delete_knowledge_lastElement_deletesRow_andKpLink() {
        long rec = seedRecord("A", "B");
        long kpId = seedKp("活名");
        long ikq = seedKnowledge(rec, "t", "[\"only\"]");
        seedLink(ikq, kpId, "快照");
        service.deleteQuestion("knowledge", ikq, 0);
        assertEquals(0, count("interview_knowledge_question", ikq), "数组删空→整行删除");
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM interview_question_kp_link WHERE interview_knowledge_question_id=?",
                Integer.class, ikq), "kp_link 级联删除");
    }

    @Test
    void delete_other_deletesRow() {
        long rec = seedRecord("A", "B");
        long o = seedOther(rec, "c", "t");
        service.deleteQuestion("other", o, 0);
        assertEquals(0, count("interview_other_question", o));
    }

    // ===== utils =====

    private String jsonElem(String table, long id, int idx) {
        return jdbc.queryForObject(
                "SELECT questions->>" + idx + " FROM " + table + " WHERE id=?", String.class, id);
    }

    private int count(String table, long id) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE id=?", Integer.class, id);
    }
}
