package com.interview.agent.learn.mapper;

import com.interview.agent.infra.db.JsonbTypeHandler;
import com.interview.agent.learn.entity.StudyQuestion;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 问题 Mapper —— tree_node(node_type='question') + question_detail 侧表。
 *
 * <p>题干存 tree_node.name；tier/rubric/recommended_answer 存 question_detail。
 * 问题节点的父可能是子话题(subtopic)或直接是知识点(knowledge_point，如面试落库/重生成题)，
 * 故 knowledge_point_id 用父节点类型 CASE 派生。
 * 写入用 PostgreSQL 数据修改 CTE 一条语句写 tree_node + question_detail。
 */
@Mapper
public interface StudyQuestionMapper {

    // p = 直接父节点（subtopic 或 knowledge_point）
    String COLS = """
            t.id, t.user_id,
            CASE WHEN p.node_type = 'subtopic' THEN p.parent_id ELSE p.id END AS knowledge_point_id,
            CASE WHEN p.node_type = 'subtopic' THEN t.parent_id ELSE NULL END AS subtopic_id,
            t.name AS content, q.tier, q.rubric_template, q.recommended_answer,
            t.sort_order, t.created_at
            """;

    String FROM = """
             FROM tree_node t
             JOIN question_detail q ON q.node_id = t.id
             JOIN tree_node p ON p.id = t.parent_id
            """;

    // 归属某 KP 的问题：父是该 KP 下的子话题，或父直接是该 KP
    String UNDER_KP = """
            (   (p.node_type = 'subtopic' AND p.parent_id = #{kpId})
             OR (p.node_type = 'knowledge_point' AND p.id = #{kpId}) )
            """;

    @Select("SELECT " + COLS + FROM + " WHERE t.node_type = 'question' AND " + UNDER_KP + " ORDER BY t.sort_order, t.id")
    List<StudyQuestion> findByKpId(@Param("kpId") long kpId);

    @Select("SELECT " + COLS + FROM + " WHERE t.node_type = 'question' AND t.parent_id = #{subtopicId} ORDER BY t.sort_order, t.id")
    List<StudyQuestion> findBySubtopic(@Param("subtopicId") long subtopicId);

    @Select("SELECT " + COLS + FROM + " WHERE t.id = #{id}")
    java.util.Optional<StudyQuestion> findById(@Param("id") long id);

    @Select("""
            SELECT EXISTS(
              SELECT 1 FROM tree_node t JOIN tree_node p ON p.id = t.parent_id
              WHERE t.node_type = 'question' AND
              (   (p.node_type = 'subtopic' AND p.parent_id = #{kpId})
               OR (p.node_type = 'knowledge_point' AND p.id = #{kpId}) )
            )
            """)
    boolean existsByKpId(@Param("kpId") long kpId);

    /** 直接挂在 KP 下新增问题（rubric/answer 可空，懒补）。返回新节点 id。 */
    @Select("""
            WITH n AS (
              INSERT INTO tree_node (tree_kind, user_id, parent_id, name, level, node_type, sort_order)
              VALUES ('knowledge',
                      (SELECT user_id FROM tree_node WHERE id = #{kpId}),
                      #{kpId}, #{content},
                      (SELECT level + 1 FROM tree_node WHERE id = #{kpId}),
                      'question', #{sortOrder})
              RETURNING id
            )
            INSERT INTO question_detail (node_id, tier, rubric_template, recommended_answer)
            SELECT id, 'core',
                   #{rubricTemplate,typeHandler=com.interview.agent.infra.db.JsonbTypeHandler,jdbcType=OTHER},
                   #{recommendedAnswer,typeHandler=com.interview.agent.infra.db.JsonbTypeHandler,jdbcType=OTHER}
            FROM n
            RETURNING node_id AS id
            """)
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    long insert(@Param("kpId") long kpId,
                @Param("content") String content,
                @Param("rubricTemplate") Object rubricTemplate,
                @Param("recommendedAnswer") Object recommendedAnswer,
                @Param("sortOrder") int sortOrder);

    /** Step A 用：挂在子话题下的目标题（tier 由 LLM 标注，rubric 空——首次答题懒补）。返回新节点 id。 */
    @Select("""
            WITH n AS (
              INSERT INTO tree_node (tree_kind, user_id, parent_id, name, level, node_type, sort_order)
              VALUES ('knowledge',
                      (SELECT user_id FROM tree_node WHERE id = #{subtopicId}),
                      #{subtopicId}, #{content},
                      (SELECT level + 1 FROM tree_node WHERE id = #{subtopicId}),
                      'question', #{sortOrder})
              RETURNING id
            )
            INSERT INTO question_detail (node_id, tier, rubric_template, recommended_answer)
            SELECT id, #{tier}, '[]'::jsonb, NULL FROM n
            RETURNING node_id AS id
            """)
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    long insertForSubtopic(@Param("subtopicId") long subtopicId,
                           @Param("content") String content,
                           @Param("tier") String tier,
                           @Param("sortOrder") int sortOrder);

    /** 删除某 KP 下所有"无作答历史"的问题节点，返回删除条数。用于 regenerate-questions。 */
    @Delete("""
            DELETE FROM tree_node t
            USING tree_node p
            WHERE p.id = t.parent_id AND t.node_type = 'question'
              AND (   (p.node_type = 'subtopic' AND p.parent_id = #{kpId})
                   OR (p.node_type = 'knowledge_point' AND p.id = #{kpId}) )
              AND NOT EXISTS (SELECT 1 FROM question_attempt a WHERE a.question_id = t.id)
            """)
    int deleteUnattemptedByKpId(@Param("kpId") long kpId);

    /** 按 id 删单个问题节点，带 kp 归属校验防越权；返回受影响行数。 */
    @Delete("""
            DELETE FROM tree_node t
            USING tree_node p
            WHERE t.id = #{id} AND t.node_type = 'question' AND p.id = t.parent_id
              AND (   (p.node_type = 'subtopic' AND p.parent_id = #{kpId})
                   OR (p.node_type = 'knowledge_point' AND p.id = #{kpId}) )
            """)
    int deleteByIdAndKp(@Param("id") long id, @Param("kpId") long kpId);

    /** 直接挂 KP 下问题的 max(sort_order)（regenerate 追加用）；无则 -1。 */
    @Select("SELECT COALESCE(MAX(sort_order), -1) FROM tree_node WHERE parent_id = #{kpId} AND node_type = 'question'")
    int maxSortOrder(@Param("kpId") long kpId);

    /** 懒生成：回填单题的 rubric_template + recommended_answer（首次答题时调）。 */
    @org.apache.ibatis.annotations.Update("""
            UPDATE question_detail SET
              rubric_template = #{rubricTemplate,typeHandler=com.interview.agent.infra.db.JsonbTypeHandler,jdbcType=OTHER},
              recommended_answer = #{recommendedAnswer,typeHandler=com.interview.agent.infra.db.JsonbTypeHandler,jdbcType=OTHER}
            WHERE node_id = #{id}
            """)
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    int updateRubric(@Param("id") long id,
                     @Param("rubricTemplate") Object rubricTemplate,
                     @Param("recommendedAnswer") Object recommendedAnswer);

    /** 切换单题 tier（core/ext）；带 kp 归属校验防越权，返回受影响行数。 */
    @org.apache.ibatis.annotations.Update("""
            UPDATE question_detail SET tier = #{tier}
            WHERE node_id = #{id} AND node_id IN (
              SELECT t.id FROM tree_node t JOIN tree_node p ON p.id = t.parent_id
              WHERE t.node_type = 'question'
                AND (   (p.node_type = 'subtopic' AND p.parent_id = #{kpId})
                     OR (p.node_type = 'knowledge_point' AND p.id = #{kpId}) )
            )
            """)
    int updateTier(@Param("id") long id, @Param("kpId") long kpId, @Param("tier") String tier);
}
