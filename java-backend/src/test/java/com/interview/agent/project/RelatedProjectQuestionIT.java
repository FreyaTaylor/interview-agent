package com.interview.agent.project;

import com.interview.agent.auth.CurrentUserTestSupport;
import com.interview.agent.interview.service.InterviewRelatedQuestionService;
import com.interview.agent.project.dto.RelatedProjectQuestion;
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
 * P5 特征测试（DB 集成）：项目 → 相关面试真题（project_node_id 落在项目子树内，递归 CTE）。
 */
@SpringBootTest
@Transactional
class RelatedProjectQuestionIT {

    @Autowired
    private InterviewRelatedQuestionService relatedQuestionService;
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

    private long seedProjectNode(String name, Long parentId, int level) {
        if (parentId == null) {
            return jdbc.queryForObject(
                    "INSERT INTO tree_node (tree_kind, node_type, name, level, sort_order) "
                            + "VALUES ('project', 'project', ?, ?, 0) RETURNING id", Long.class, name, level);
        }
        return jdbc.queryForObject(
                "INSERT INTO tree_node (tree_kind, node_type, name, level, sort_order, parent_id) "
                        + "VALUES ('project', 'question', ?, ?, 0, ?) RETURNING id", Long.class, name, level, parentId);
    }

    @Test
    void byProject_returnsQuestionsInSubtree() {
        long root = seedProjectNode("测试项目", null, 1);
        long qNode = seedProjectNode("项目问题节点", root, 2);   // 真题匹配到的是子节点

        Long recordId = jdbc.queryForObject(
                "INSERT INTO interview_record (raw_text, company, position) VALUES ('原文', '测试公司', '后端') RETURNING id",
                Long.class);
        jdbc.update("INSERT INTO interview_project_question "
                + "(interview_record_id, project_node_id, project_name, questions) "
                + "VALUES (?, ?, '测试项目', '[\"你这个项目的分库分表怎么做的？\"]'::jsonb)", recordId, qNode);

        // 按项目根查 → 应命中挂在其子节点下的真题
        List<RelatedProjectQuestion> related = relatedQuestionService.byProject(root);
        assertEquals(1, related.size(), "应查到项目子树内的一道真题");
        RelatedProjectQuestion q = related.get(0);
        assertEquals(List.of("你这个项目的分库分表怎么做的？"), q.questions());
        assertEquals("测试公司", q.company());
        assertEquals(recordId, q.interviewRecordId());
    }

    @Test
    void byProject_returnsEmptyForUnrelatedProject() {
        long other = seedProjectNode("无关项目", null, 1);
        assertTrue(relatedQuestionService.byProject(other).isEmpty(), "无关项目应返回空");
    }
}
