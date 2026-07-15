package com.interview.agent.project.mapper;

import com.interview.agent.project.entity.ProjectNode;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Optional;

/**
 * 项目树节点 Mapper —— 统一节点树 {@code tree_node} 中 {@code tree_kind='project'} 的部分。
 *
 * <p>node_type 按 level 固定：1=project / 2=topic / 3=question（项目树固定三层）。
 * embedding 写入用 #{embeddingLiteral}::vector，与知识树一致。
 */
@Mapper
public interface ProjectNodeMapper {

    String COLS = """
            id, user_id, parent_id, name, level, node_type,
            sort_order, created_at, updated_at
            """;

    // ===== 查询 =====

    /** 列出当前用户的全部项目节点；按 level → sort_order → id 给前端组树。 */
    @Select("SELECT " + COLS + " FROM tree_node WHERE tree_kind = 'project' AND user_id = #{userId} ORDER BY level, sort_order, id")
    List<ProjectNode> findAllOrdered(@Param("userId") long userId);

    @Select("SELECT " + COLS + " FROM tree_node WHERE id = #{id} AND user_id = #{userId}")
    Optional<ProjectNode> findById(@Param("id") long id, @Param("userId") long userId);

    @Select("SELECT id FROM tree_node WHERE parent_id = #{parentId} AND user_id = #{userId}")
    List<Long> findChildIds(@Param("parentId") long parentId, @Param("userId") long userId);

    /** 取当前用户的所有项目根节点（level=1，parent_id IS NULL）；用于 from-text 同名/语义去重。 */
    @Select("SELECT " + COLS + " FROM tree_node WHERE tree_kind = 'project' AND parent_id IS NULL AND user_id = #{userId} ORDER BY id")
    List<ProjectNode> findRoots(@Param("userId") long userId);

    // ===== 面试匹配（get_or_create + embedding 召回）=====

    /** 按 level + name 取当前用户的项目节点 id（复刻 get_or_create「未命名项目」根的 filter_by）。 */
    @Select("SELECT id FROM tree_node WHERE tree_kind = 'project' AND user_id = #{userId} AND level = #{level} AND name = #{name} ORDER BY id LIMIT 1")
    Optional<Long> findIdByLevelAndName(@Param("level") short level, @Param("name") String name, @Param("userId") long userId);

    /**
     * 在指定 topic 下召回最相似的 question 叶子（embedding 非空）。
     * 复刻 match_or_create_question：{@code (embedding <=> :vec) AS distance} 取最近一条。
     */
    @Select("""
            SELECT id, name, NULL AS path, (embedding <=> #{vec}::vector) AS distance
            FROM tree_node
            WHERE parent_id = #{topicId} AND node_type = 'question' AND embedding IS NOT NULL
              AND user_id = #{userId}
            ORDER BY embedding <=> #{vec}::vector
            LIMIT 1
            """)
    Optional<com.interview.agent.interview.matcher.NodeMatch> findNearestLeafUnderTopic(
            @Param("topicId") long topicId, @Param("vec") String vec, @Param("userId") long userId);

    /** 累积问题表述：name = 旧 \\ 新（复刻 match_or_create_question 命中分支）；仅限当前用户。 */
    @Update("UPDATE tree_node SET name = #{name}, updated_at = NOW() WHERE id = #{id} AND user_id = #{userId}")
    int updateName(@Param("id") long id, @Param("userId") long userId, @Param("name") String name);

    /** 取某父节点下指定 level 的子节点列表，按 sort_order, id 排序。root→L2 话题、L2→L3 题目。 */
    @Select("SELECT " + COLS + " FROM tree_node"
            + " WHERE parent_id = #{parentId} AND level = #{level} AND user_id = #{userId}"
            + " ORDER BY sort_order, id")
    List<ProjectNode> findChildrenByLevel(@Param("parentId") long parentId,
                                          @Param("level") short level,
                                          @Param("userId") long userId);

    /** 数某项目根（L1）下所有 L3 叶子题目数。供 projects-list real_question_count 使用。 */
    @Select("SELECT COUNT(*) FROM tree_node leaf"
            + " JOIN tree_node topic ON leaf.parent_id = topic.id"
            + " WHERE topic.parent_id = #{rootId} AND topic.level = 2 AND leaf.level = 3 AND leaf.user_id = #{userId}")
    int countLeavesUnderRoot(@Param("rootId") long rootId, @Param("userId") long userId);

    /**
     * 取以 rootId 为根的整棵子树（含 root 自身）最深 level；空树或仅 root 返回 root 自身 level。
     * 用于跨父移动时校验"移动后是否超过项目树 3 层硬限"。
     */
    @Select("""
            WITH RECURSIVE subtree AS (
              SELECT id, level FROM tree_node WHERE id = #{rootId} AND user_id = #{userId}
              UNION ALL
              SELECT n.id, n.level FROM tree_node n
                JOIN subtree s ON n.parent_id = s.id AND n.user_id = #{userId}
            )
            SELECT COALESCE(MAX(level), 0) FROM subtree
            """)
    int findMaxLevelInSubtree(@Param("rootId") long rootId, @Param("userId") long userId);

    // ===== 插入 =====

    @Select("""
            INSERT INTO tree_node
              (tree_kind, user_id, parent_id, name, level, node_type, sort_order)
            VALUES ('project', #{userId}, #{parentId}, #{name}, #{level}, #{nodeType}, #{sortOrder})
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
            VALUES ('project', #{userId}, #{parentId}, #{name}, #{level}, #{nodeType}, #{sortOrder},
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

    /** name / sortOrder 用 COALESCE：null 表示不变；仅限当前用户。 */
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

    /** 跨父移动：一次 UPDATE 写 parent_id / level / node_type（node_type 由 level 决定）；仅限当前用户。 */
    @Update("""
            UPDATE tree_node SET
              parent_id = #{parentId},
              level = #{level},
              node_type = #{nodeType},
              updated_at = NOW()
            WHERE id = #{id} AND user_id = #{userId}
            """)
    int moveParent(@Param("id") long id,
                   @Param("userId") long userId,
                   @Param("parentId") Long parentId,
                   @Param("level") short level,
                   @Param("nodeType") String nodeType);

    @Update("UPDATE tree_node SET sort_order = #{sortOrder}, updated_at = NOW() WHERE id = #{id} AND user_id = #{userId}")
    int updateSortOrder(@Param("id") long id, @Param("userId") long userId, @Param("sortOrder") int sortOrder);

    /**
     * 把以 rootId 为根的子树（不含 root 自身）所有节点 level += delta，
     * node_type 按新 level 映射：1=project / 2=topic / 3=question（项目树固定三层）。
     */
    @Update("""
            WITH RECURSIVE descendants AS (
              SELECT id, level FROM tree_node WHERE parent_id = #{rootId} AND user_id = #{userId}
              UNION ALL
              SELECT n.id, n.level FROM tree_node n
                JOIN descendants d ON n.parent_id = d.id AND n.user_id = #{userId}
            )
            UPDATE tree_node SET
              level = level + #{delta},
              node_type = CASE
                WHEN (level + #{delta}) = 1 THEN 'project'
                WHEN (level + #{delta}) = 2 THEN 'topic'
                ELSE 'question' END,
              updated_at = NOW()
            WHERE id IN (SELECT id FROM descendants)
            """)
    int shiftDescendantLevels(@Param("rootId") long rootId, @Param("userId") long userId, @Param("delta") int delta);

    // ===== 删除 =====

    @Delete("""
            <script>
            DELETE FROM tree_node WHERE user_id = #{userId} AND id IN
            <foreach collection='ids' item='i' open='(' separator=',' close=')'>#{i}</foreach>
            </script>
            """)
    int deleteByIds(@Param("ids") List<Long> ids, @Param("userId") long userId);
}
