package com.interview.agent.learn.mapper;

import com.interview.agent.learn.entity.KnowledgeSubtopic;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Optional;

/**
 * knowledge_subtopic 表 Mapper。
 *
 * <p>JSONB 字段 {@code followups} 写入走 {@code JsonbTypeHandler}（显式声明 typeHandler）；
 * 读取由全局 typeHandlers-package 自动匹配，落到 record 字段 {@code List<Map<String,Object>>}。
 */
@Mapper
public interface KnowledgeSubtopicMapper {

    String COLS = """
            id, kp_id, title, body_md, importance, followups,
            sort_order, source, content_status, mastery_level, user_id, created_at
            """;

    @Select("SELECT " + COLS + " FROM knowledge_subtopic WHERE kp_id = #{kpId} ORDER BY sort_order, id")
    List<KnowledgeSubtopic> findByKp(@Param("kpId") long kpId);

    @Select("SELECT " + COLS + " FROM knowledge_subtopic WHERE id = #{id}")
    Optional<KnowledgeSubtopic> findById(@Param("id") long id);

    @Select("SELECT EXISTS(SELECT 1 FROM knowledge_subtopic WHERE kp_id = #{kpId})")
    boolean existsByKp(@Param("kpId") long kpId);

    /**
     * 取该 KP 的事务级 advisory 锁，序列化"生成子话题"这一临界区，防并发重复生成。
     * <p>阻塞直到拿到锁；锁在**当前事务提交/回滚时自动释放**（xact 级）。
     * 前一个生成事务提交后，本事务再查 {@link #findByKp} 即可看到已生成数据、直接返回，实现幂等。
     * <p>子话题生成是唯一按 kp_id 加此类锁的地方，用单参 bigint 形式即可，无跨功能冲突风险。
     */
    @Select("SELECT 1 FROM (SELECT pg_advisory_xact_lock(#{kpId})) AS _lock")
    Integer acquireGenLock(@Param("kpId") long kpId);

    /**
     * 子话题级正文生成锁（Step B）：用**双参 advisory 锁**（namespace=1, subtopicId），
     * 与单参的 KP 级锁（{@link #acquireGenLock}）处于**不同锁空间**，避免 id 碰撞。
     */
    @Select("SELECT 1 FROM (SELECT pg_advisory_xact_lock(1, #{subtopicId})) AS _lock")
    Integer acquireSubtopicContentLock(@Param("subtopicId") int subtopicId);

    /** 返回 max(sort_order)；KP 下无数据时返回 0。 */
    @Select("SELECT COALESCE(MAX(sort_order), 0) FROM knowledge_subtopic WHERE kp_id = #{kpId}")
    int maxSortOrder(@Param("kpId") long kpId);

    /** 新增子话题；返回新行 id。 */
    @Select("""
            INSERT INTO knowledge_subtopic
              (kp_id, title, body_md, importance, sort_order, source)
            VALUES (
              #{kpId}, #{title}, #{bodyMd}, #{importance}, #{sortOrder}, #{source}
            )
            RETURNING id
            """)
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    long insert(@Param("kpId") long kpId,
                @Param("title") String title,
                @Param("bodyMd") String bodyMd,
                @Param("importance") int importance,
                @Param("sortOrder") int sortOrder,
                @Param("source") String source);

    /** Step A 用：只落标题+目标题的"待生成正文"子话题（body_md=null, content_status='pending'）。返回新行 id。 */
    @Select("""
            INSERT INTO knowledge_subtopic
              (kp_id, title, body_md, importance, sort_order, source, content_status)
            VALUES (
              #{kpId}, #{title}, NULL, #{importance}, #{sortOrder}, #{source}, 'pending'
            )
            RETURNING id
            """)
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    long insertPending(@Param("kpId") long kpId,
                       @Param("title") String title,
                       @Param("importance") int importance,
                       @Param("sortOrder") int sortOrder,
                       @Param("source") String source);

    /** Step B 用：回填正文并置 content_status='ready'。 */
    @Update("UPDATE knowledge_subtopic SET body_md = #{bodyMd}, content_status = 'ready' WHERE id = #{id} AND kp_id = #{kpId}")
    int updateBody(@Param("id") long id, @Param("kpId") long kpId, @Param("bodyMd") String bodyMd);

    /** finish 钩子用：写回子话题级掌握度（null 允许，表示该子话题未答过题）。 */
    @Update("UPDATE knowledge_subtopic SET mastery_level = #{mastery} WHERE id = #{id}")
    int updateMastery(@Param("id") long id, @Param("mastery") Integer mastery);

    /**
     * 追加 followup 到 JSONB 数组末尾。
     * <p>使用 {@code followups || jsonb_build_array(...)} 在 SQL 层 append，避免读改写竞态。
     */
    @Update("""
            UPDATE knowledge_subtopic
            SET followups = followups || jsonb_build_array(
                jsonb_build_object(
                    'q', CAST(#{question} AS TEXT),
                    'a', CAST(#{answer} AS TEXT),
                    'created_at', to_char(now() AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"')
                )
            )
            WHERE id = #{id}
            """)
    int appendFollowup(@Param("id") long id,
                       @Param("question") String question,
                       @Param("answer") String answer);

    @Delete("DELETE FROM knowledge_subtopic WHERE kp_id = #{kpId}")
    int deleteByKp(@Param("kpId") long kpId);

    /** 按 id 删除单条子话题，附带 kp_id 校验防越权（误传别的 KP 的 id 不会生效）。 */
    @Delete("DELETE FROM knowledge_subtopic WHERE id = #{id} AND kp_id = #{kpId}")
    int deleteById(@Param("id") long id, @Param("kpId") long kpId);
}
