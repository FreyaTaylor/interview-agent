package com.interview.agent.interview.exp.mapper;

import com.interview.agent.interview.exp.dto.InterviewExpNodeView;
import com.interview.agent.interview.exp.entity.InterviewExpNode;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Optional;

/**
 * 面经树节点 Mapper —— 统一表 {@code tree_node} 中 {@code tree_kind='interview_exp'} 的部分。
 *
 * <p>结构与知识树 {@code KnowledgeNodeMapper} 平行，仅 {@code tree_kind} 不同、node_type 用 domain/question。
 * SQL 与注解同文件；snake_case↔camelCase 由全局配置处理；Record 结果映射靠 -parameters。
 */
@Mapper
public interface InterviewExpNodeMapper {

    // ===== 查询 =====

    /**
     * 列出全部面经节点（含出现频率）。频率 = 该问题在多少条 {@code question_source_link}（不同来源）中出现；
     * 域节点 LEFT JOIN 无匹配 → 0。按 level/sort_order/id 有序，前端组装成树。
     */
    @Select("""
            SELECT t.id AS id, t.parent_id AS parentId, t.name AS name, t.level AS level,
                   t.node_type AS nodeType, t.sort_order AS sortOrder,
                   COALESCE(c.freq, 0) AS frequency
            FROM tree_node t
            LEFT JOIN (
                SELECT question_node_id, COUNT(*) AS freq
                FROM question_source_link GROUP BY question_node_id
            ) c ON c.question_node_id = t.id
            WHERE t.tree_kind = 'interview_exp' AND t.user_id = #{userId}
            ORDER BY t.level, t.sort_order, t.id
            """)
    List<InterviewExpNodeView> findAllOrdered(@Param("userId") long userId);

    @Select("""
            SELECT id, parent_id, name, level, node_type, sort_order
            FROM tree_node WHERE id = #{id} AND user_id = #{userId} AND tree_kind = 'interview_exp'
            """)
    Optional<InterviewExpNode> findById(@Param("id") long id, @Param("userId") long userId);

    @Select("SELECT id FROM tree_node WHERE parent_id = #{parentId} AND user_id = #{userId}")
    List<Long> findChildIds(@Param("parentId") long parentId, @Param("userId") long userId);

    @Select("SELECT EXISTS(SELECT 1 FROM tree_node WHERE parent_id = #{parentId} AND user_id = #{userId})")
    boolean hasChildren(@Param("parentId") long parentId, @Param("userId") long userId);

    /** 取全部知识域节点（level1/domain），供解析时喂 prompt「已有域清单」+ 域名匹配复用。 */
    @Select("""
            SELECT id, parent_id, name, level, node_type, sort_order
            FROM tree_node
            WHERE tree_kind = 'interview_exp' AND node_type = 'domain' AND user_id = #{userId}
            ORDER BY id
            """)
    List<InterviewExpNode> findDomains(@Param("userId") long userId);

    /**
     * 在指定域下召回与 vec 最近的一个问题（pgvector 余弦距离升序）。
     * 用于问题级语义去重：距离 ≤ 阈值 → 判为同一问题（计频），否则新建。
     */
    @Select("""
            SELECT n.id AS id, (n.embedding <=> #{vec}::vector) AS distance
            FROM tree_node n
            WHERE n.tree_kind = 'interview_exp' AND n.node_type = 'question'
              AND n.parent_id = #{domainId} AND n.embedding IS NOT NULL AND n.user_id = #{userId}
            ORDER BY n.embedding <=> #{vec}::vector
            LIMIT 1
            """)
    Optional<com.interview.agent.interview.exp.dto.ExpQuestionMatch> findNearestQuestionInDomain(
            @Param("userId") long userId, @Param("domainId") long domainId, @Param("vec") String vec);

    // ===== 插入（INSERT ... RETURNING id 用 @Select）=====

    @Select("""
            INSERT INTO tree_node
              (tree_kind, user_id, parent_id, name, level, node_type, sort_order)
            VALUES ('interview_exp', #{userId}, #{parentId}, #{name}, #{level}, #{nodeType}, #{sortOrder})
            RETURNING id
            """)
    long insertWithoutEmbedding(@Param("userId") long userId,
                                @Param("parentId") Long parentId,
                                @Param("name") String name,
                                @Param("level") short level,
                                @Param("nodeType") String nodeType,
                                @Param("sortOrder") int sortOrder);

    @Select("""
            INSERT INTO tree_node
              (tree_kind, user_id, parent_id, name, level, node_type, sort_order, embedding)
            VALUES ('interview_exp', #{userId}, #{parentId}, #{name}, #{level}, #{nodeType}, #{sortOrder},
                    #{embeddingLiteral}::vector)
            RETURNING id
            """)
    long insertWithEmbedding(@Param("userId") long userId,
                             @Param("parentId") Long parentId,
                             @Param("name") String name,
                             @Param("level") short level,
                             @Param("nodeType") String nodeType,
                             @Param("sortOrder") int sortOrder,
                             @Param("embeddingLiteral") String embeddingLiteral);

    // ===== 更新 =====

    /** name / sortOrder 用 COALESCE：null 表示不变；仅限当前用户 */
    @Update("""
            UPDATE tree_node SET
              name = COALESCE(#{name}, name),
              sort_order = COALESCE(#{sortOrder}, sort_order),
              updated_at = NOW()
            WHERE id = #{id} AND user_id = #{userId}
            """)
    int updateBasic(@Param("id") long id,
                    @Param("userId") long userId,
                    @Param("name") String name,
                    @Param("sortOrder") Integer sortOrder);

    /** 跨父移动：一次 UPDATE 写 parent_id / level / node_type */
    @Update("""
            UPDATE tree_node SET
              parent_id = #{parentId}, level = #{level}, node_type = #{nodeType}, updated_at = NOW()
            WHERE id = #{id} AND user_id = #{userId}
            """)
    int moveParent(@Param("id") long id,
                   @Param("userId") long userId,
                   @Param("parentId") Long parentId,
                   @Param("level") short level,
                   @Param("nodeType") String nodeType);

    @Update("UPDATE tree_node SET sort_order = #{sortOrder}, updated_at = NOW() WHERE id = #{id} AND user_id = #{userId}")
    int updateSortOrder(@Param("id") long id, @Param("userId") long userId, @Param("sortOrder") int sortOrder);

    @Update("UPDATE tree_node SET node_type = #{nodeType}, updated_at = NOW() WHERE id = #{id} AND user_id = #{userId}")
    int updateNodeType(@Param("id") long id, @Param("userId") long userId, @Param("nodeType") String nodeType);

    /** 把 rootId 子树（不含自身）level += delta；跨父拖动后平移，避免前端缩进被"打扁"。 */
    @Update("""
            WITH RECURSIVE descendants AS (
              SELECT id FROM tree_node WHERE parent_id = #{rootId}
              UNION ALL
              SELECT n.id FROM tree_node n JOIN descendants d ON n.parent_id = d.id
            )
            UPDATE tree_node SET level = level + #{delta}, updated_at = NOW()
            WHERE id IN (SELECT id FROM descendants) AND user_id = #{userId}
            """)
    int shiftDescendantLevels(@Param("rootId") long rootId, @Param("userId") long userId, @Param("delta") int delta);

    // ===== 删除 =====

    @org.apache.ibatis.annotations.Delete("""
            <script>
            DELETE FROM tree_node WHERE user_id = #{userId} AND id IN
            <foreach collection="ids" item="i" open="(" separator="," close=")">#{i}</foreach>
            </script>
            """)
    int deleteByIds(@Param("userId") long userId, @Param("ids") List<Long> ids);
}
