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
 * 子话题 Mapper —— tree_node(node_type='subtopic') + subtopic_detail 侧表。
 *
 * <p>读取用 JOIN 把骨架(tree_node)与内容(subtopic_detail)拼回 {@link KnowledgeSubtopic}；
 * 写入用 PostgreSQL 数据修改 CTE（WITH ... INSERT ... RETURNING）一条语句写两表。
 * 子话题 level = 所属知识点 level + 1；user_id 继承知识点。
 */
@Mapper
public interface KnowledgeSubtopicMapper {

    String COLS = """
            t.id, t.parent_id AS kp_id, t.name AS title, d.body_md,
            t.sort_order, d.content_status, d.mastery_level, t.user_id, t.created_at
            """;

    String FROM = " FROM tree_node t JOIN subtopic_detail d ON d.node_id = t.id ";

    @Select("SELECT " + COLS + FROM + " WHERE t.node_type = 'subtopic' AND t.parent_id = #{kpId} ORDER BY t.sort_order, t.id")
    List<KnowledgeSubtopic> findByKp(@Param("kpId") long kpId);

    @Select("SELECT " + COLS + FROM + " WHERE t.id = #{id}")
    Optional<KnowledgeSubtopic> findById(@Param("id") long id);

    /**
     * 取该 KP 的事务级 advisory 锁，序列化"生成子话题"这一临界区，防并发重复生成。
     * <p>阻塞直到拿到锁；锁在**当前事务提交/回滚时自动释放**（xact 级）。
     * 前一个生成事务提交后，本事务再查 {@link #findByKp} 即可看到已生成数据、直接返回，实现幂等。
     */
    @Select("SELECT 1 FROM (SELECT pg_advisory_xact_lock(#{kpId})) AS _lock")
    Integer acquireGenLock(@Param("kpId") long kpId);

    /**
     * 子话题级正文生成锁（Step B）：用**双参 advisory 锁**（namespace=1, subtopicId），
     * 与单参的 KP 级锁（{@link #acquireGenLock}）处于**不同锁空间**，避免 id 碰撞。
     */
    @Select("SELECT 1 FROM (SELECT pg_advisory_xact_lock(1, #{subtopicId})) AS _lock")
    Integer acquireSubtopicContentLock(@Param("subtopicId") int subtopicId);

    /** 返回该 KP 下子话题 max(sort_order)；无数据时返回 0。 */
    @Select("SELECT COALESCE(MAX(sort_order), 0) FROM tree_node WHERE parent_id = #{kpId} AND node_type = 'subtopic'")
    int maxSortOrder(@Param("kpId") long kpId);

    /** Step A 用：只落标题的"待生成正文"子话题（body_md=null, content_status='pending'）。返回新节点 id。 */
    @Select("""
            WITH n AS (
              INSERT INTO tree_node (tree_kind, user_id, parent_id, name, level, node_type, sort_order)
              VALUES ('knowledge',
                      (SELECT user_id FROM tree_node WHERE id = #{kpId}),
                      #{kpId}, #{title},
                      (SELECT level + 1 FROM tree_node WHERE id = #{kpId}),
                      'subtopic', #{sortOrder})
              RETURNING id
            )
            INSERT INTO subtopic_detail (node_id, body_md, content_status)
            SELECT id, NULL, 'pending' FROM n
            RETURNING node_id AS id
            """)
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    long insertPending(@Param("kpId") long kpId,
                       @Param("title") String title,
                       @Param("sortOrder") int sortOrder);

    /** Step B 用：回填正文并置 content_status='ready'（校验节点属于该 KP）。 */
    @Update("""
            UPDATE subtopic_detail d
            SET body_md = #{bodyMd}, content_status = 'ready'
            FROM tree_node t
            WHERE d.node_id = t.id AND t.id = #{id} AND t.parent_id = #{kpId}
            """)
    int updateBody(@Param("id") long id, @Param("kpId") long kpId, @Param("bodyMd") String bodyMd);

    /** finish 钩子用：写回子话题级掌握度（null 允许，表示该子话题未答过题）。 */
    @Update("UPDATE subtopic_detail SET mastery_level = #{mastery} WHERE node_id = #{id}")
    int updateMastery(@Param("id") long id, @Param("mastery") Integer mastery);

    /** 删该 KP 下所有子话题节点（tree_node 级联删 subtopic_detail 及子问题节点 + question_detail）。 */
    @Delete("DELETE FROM tree_node WHERE parent_id = #{kpId} AND node_type = 'subtopic'")
    int deleteByKp(@Param("kpId") long kpId);

    /** 按 id 删单个子话题节点，带 kp_id 校验防越权。 */
    @Delete("DELETE FROM tree_node WHERE id = #{id} AND parent_id = #{kpId} AND node_type = 'subtopic'")
    int deleteById(@Param("id") long id, @Param("kpId") long kpId);
}
