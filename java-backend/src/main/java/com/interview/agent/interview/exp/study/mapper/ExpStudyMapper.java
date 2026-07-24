package com.interview.agent.interview.exp.study.mapper;

import com.interview.agent.interview.exp.study.dto.ExpStudyTreeNode;
import com.interview.agent.interview.exp.study.dto.ExpDetailRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Optional;

/**
 * 「看看面经」学习页 Mapper —— 读面经树（含自评/频率/内容状态）+ 问题内容侧表读写。
 *
 * <p>骨架在 {@code tree_node(tree_kind='interview_exp')}，内容在 {@code interview_exp_question_detail}（1:1）。
 * snake_case↔camelCase 全局配置；Record 映射靠 -parameters。
 */
@Mapper
public interface ExpStudyMapper {

    /**
     * 面经树（域 + 问题）平铺，供侧栏。问题带 self_mastery(自评)/frequency(出现频率)/content_status(内容状态)。
     * 域节点 content_status 为 null、frequency 0。
     */
    @Select("""
            SELECT t.id AS id, t.parent_id AS parentId, t.name AS name, t.level AS level,
                   t.node_type AS nodeType, t.sort_order AS sortOrder,
                   COALESCE(t.self_mastery, 0) AS selfMastery,
                   COALESCE(c.freq, 0) AS frequency,
                   d.content_status AS contentStatus
            FROM tree_node t
            LEFT JOIN (
                SELECT question_node_id, COUNT(*) AS freq
                FROM question_source_link GROUP BY question_node_id
            ) c ON c.question_node_id = t.id
            LEFT JOIN interview_exp_question_detail d ON d.node_id = t.id
            WHERE t.tree_kind = 'interview_exp' AND t.user_id = #{userId}
            ORDER BY t.level, t.sort_order, t.id
            """)
    List<ExpStudyTreeNode> findTree(@Param("userId") long userId);

    /**
     * 单个问题详情（骨架 name/域名/自评/频率 + 内容侧表 body/status/rubric/answer）。
     * rubric/answer 用 {@code ::text} 取 JSON 文本，Service 再解析为数组。
     * 仅认当前用户的 interview_exp question 节点（IDOR 校验）。
     */
    @Select("""
            SELECT t.id AS questionId, t.name AS name, p.name AS domainName,
                   COALESCE(t.self_mastery, 0) AS selfMastery,
                   COALESCE(c.freq, 0) AS frequency,
                   d.body_md AS bodyMd, d.content_status AS contentStatus,
                   d.rubric_template::text AS rubricTemplate,
                   d.recommended_answer::text AS recommendedAnswer
            FROM tree_node t
            LEFT JOIN tree_node p ON p.id = t.parent_id
            LEFT JOIN (
                SELECT question_node_id, COUNT(*) AS freq
                FROM question_source_link GROUP BY question_node_id
            ) c ON c.question_node_id = t.id
            LEFT JOIN interview_exp_question_detail d ON d.node_id = t.id
            WHERE t.id = #{id} AND t.user_id = #{userId}
              AND t.tree_kind = 'interview_exp' AND t.node_type = 'question'
            """)
    Optional<ExpDetailRow> findDetail(@Param("id") long id, @Param("userId") long userId);

    /** 幂等建内容侧表行（pending 空正文）；已存在则不动。 */
    @Update("""
            INSERT INTO interview_exp_question_detail (node_id)
            VALUES (#{nodeId})
            ON CONFLICT (node_id) DO NOTHING
            """)
    int ensureDetailRow(@Param("nodeId") long nodeId);

    /** 回填讲解 + rubric + 推荐答案，置 ready。rubric/answer 走 JsonbTypeHandler 写 JSONB。 */
    @Update("""
            UPDATE interview_exp_question_detail SET
              body_md = #{bodyMd},
              content_status = 'ready',
              rubric_template = #{rubric,typeHandler=com.interview.agent.infra.db.JsonbTypeHandler,jdbcType=OTHER},
              recommended_answer = #{recommendedAnswer,typeHandler=com.interview.agent.infra.db.JsonbTypeHandler,jdbcType=OTHER},
              updated_at = NOW()
            WHERE node_id = #{nodeId}
            """)
    int updateContent(@Param("nodeId") long nodeId,
                      @Param("bodyMd") String bodyMd,
                      @Param("rubric") Object rubric,
                      @Param("recommendedAnswer") Object recommendedAnswer);

    /** 问题级事务咨询锁（防并发重复生成）；单参 bigint 锁空间（与子话题双参 int 锁互不冲突）。 */
    @Select("SELECT 1 FROM (SELECT pg_advisory_xact_lock(#{nodeId})) AS _lock")
    Integer acquireContentLock(@Param("nodeId") long nodeId);

    /** 自评掌握度写入 tree_node.self_mastery（仅 interview_exp question 节点，IDOR 校验）；null=清除。 */
    @Update("""
            UPDATE tree_node SET self_mastery = #{selfMastery}, updated_at = NOW()
            WHERE id = #{nodeId} AND user_id = #{userId}
              AND tree_kind = 'interview_exp' AND node_type = 'question'
            """)
    int updateSelfMastery(@Param("nodeId") long nodeId, @Param("userId") long userId,
                          @Param("selfMastery") Integer selfMastery);
}
