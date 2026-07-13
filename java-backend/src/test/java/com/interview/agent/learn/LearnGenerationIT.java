package com.interview.agent.learn;

import com.interview.agent.auth.CurrentUserTestSupport;
import com.interview.agent.common.LlmInvoker;
import com.interview.agent.knowledge.mapper.KnowledgeNodeMapper;
import com.interview.agent.learn.dto.LearnAssetRequest;
import com.interview.agent.learn.entity.StudyQuestion;
import com.interview.agent.learn.mapper.StudyQuestionMapper;
import com.interview.agent.learn.service.LearnContentService;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 特征测试（DB 集成）：锁住「学习内容生成把面试真题并入子话题」的当前行为（#2）。
 *
 * <p>用 @Transactional 让每个用例结束后回滚，保护本地真实库；用 @Primary 的假 {@link LlmInvoker}
 * 打桩 LLM 出口（返回带 interview_refs 的固定子话题 JSON），避免联网 + 非确定性。
 *
 * <p>这套基建（假 LLM + 事务回滚 + CurrentUser 支持）供后续 P1~P3 集成测试复用。
 */
@SpringBootTest
@Import(LearnGenerationIT.FakeLlmConfig.class)
@Transactional
class LearnGenerationIT {

    /** 打桩 LLM：普通子话题生成（LEARN_SUBTOPICS_GEN）返回固定 3 个子话题，不涉真题。 */
    @TestConfiguration
    static class FakeLlmConfig {
        @Bean
        @Primary
        LlmInvoker fakeLlmInvoker() {
            return new LlmInvoker(null, null) {
                @Override
                public <T> Optional<T> invoke(Spec spec, ResponseParser<T> parser) {
                    if (PromptKeys.LEARN_SUBTOPICS_GEN.equals(spec.promptKey())) {
                        String json = """
                                [
                                  {"title":"子话题A","target_questions":[{"q":"生成题A","tier":"core"}]},
                                  {"title":"子话题B","target_questions":[{"q":"生成题B","tier":"core"}]},
                                  {"title":"子话题C","target_questions":[{"q":"生成题C","tier":"core"}]}
                                ]
                                """;
                        try {
                            return Optional.of(parser.parse(json));
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    }
                    return Optional.empty();
                }
            };
        }
    }

    @Autowired
    private LearnContentService learnContentService;
    @Autowired
    private KnowledgeNodeMapper nodeMapper;
    @Autowired
    private StudyQuestionMapper questionMapper;
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
    void fetchContent_doesNotPullInterviewQuestionsIntoSubtopics() {
        // seed：分类 → 知识点 → 该 KP 下两道直挂面试真题（source=interview, 无子话题）
        long catId = nodeMapper.insertWithoutEmbedding(1L, null, "测试分类", (short) 1, "category", (short) 3, 0, false);
        long kpId = nodeMapper.insertWithoutEmbedding(1L, catId, "测试知识点", (short) 2, "knowledge_point", (short) 3, 0, false);
        long q1 = questionMapper.insert(kpId, "真题一", null, null, "interview", null, 1);
        long q2 = questionMapper.insert(kpId, "真题二", null, null, "interview", null, 2);

        // act：首次学习触发生成（走普通 subtopics-gen）
        learnContentService.resolveContent(new LearnAssetRequest(kpId, LearnAssetRequest.ACTION_FETCH));

        // assert 1：KP 下生成了子话题
        Integer subtopicCount = jdbc.queryForObject(
                "SELECT count(*) FROM tree_node WHERE parent_id = ? AND node_type = 'subtopic'", Integer.class, kpId);
        assertTrue(subtopicCount != null && subtopicCount >= 1, "应生成子话题");

        // assert 2（P4 新行为）：两道真题不被并入子话题 —— 仍 KP 直挂（subtopic_id 为空）、source 不变
        List<StudyQuestion> interviewQs = questionMapper.findByKpId(kpId).stream()
                .filter(q -> "interview".equals(q.source())).toList();
        assertEquals(2, interviewQs.size(), "两道真题应仍在（未被删）");
        for (StudyQuestion q : interviewQs) {
            assertTrue(q.subtopicId() == null,
                    "真题 [" + q.id() + "] 不应被 reparent 进子话题（subtopicId 应为空），实际=" + q.subtopicId());
        }

        // assert 3：两道真题的父节点仍是知识点（非 subtopic）
        Integer underKp = jdbc.queryForObject(
                "SELECT count(*) FROM tree_node t JOIN tree_node p ON p.id = t.parent_id "
                        + "WHERE t.id IN (?, ?) AND p.node_type = 'knowledge_point'", Integer.class, q1, q2);
        assertEquals(2, underKp, "两道真题的父节点都应仍是知识点");
    }
}
