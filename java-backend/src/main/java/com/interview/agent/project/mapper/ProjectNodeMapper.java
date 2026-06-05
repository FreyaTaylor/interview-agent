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
 * project_node 表 Mapper（MyBatis @ 注解）。
 *
 * <p>与 KnowledgeNodeMapper 平行；差异：
 * <ul>
 *   <li>列集少：无 interview_weight / mastery_level / study_count / is_user_created</li>
 *   <li>有 user_id 列（写入固定 1）</li>
 * </ul>
 *
 * embedding 写入用 #{embeddingLiteral}::vector，与 S1 一致。
 */
@Mapper
public interface ProjectNodeMapper {

    String COLS = """
            id, user_id, parent_id, name, level, node_type,
            sort_order, created_at, updated_at
            """;

    // ===== 查询 =====

    /** 列出全部项目节点；按 level → sort_order → id 给前端组树。 */
    @Select("SELECT " + COLS + " FROM project_node ORDER BY level, sort_order, id")
    List<ProjectNode> findAllOrdered();

    @Select("SELECT " + COLS + " FROM project_node WHERE id = #{id}")
    Optional<ProjectNode> findById(@Param("id") long id);

    @Select("SELECT id FROM project_node WHERE parent_id = #{parentId}")
    List<Long> findChildIds(@Param("parentId") long parentId);

    /** 取所有根节点（level=1，parent_id IS NULL）；用于 from-text 同名/语义去重。 */
    @Select("SELECT " + COLS + " FROM project_node WHERE parent_id IS NULL ORDER BY id")
    List<ProjectNode> findRoots();

    @Select("SELECT EXISTS(SELECT 1 FROM project_node WHERE parent_id = #{parentId})")
    boolean hasChildren(@Param("parentId") long parentId);

    /**
     * 取以 rootId 为根的整棵子树（含 root 自身）最深 level；空树或仅 root 返回 root 自身 level。
     * 用于跨父移动时校验"移动后是否超过项目树 3 层硬限"。
     */
    @Select("""
            WITH RECURSIVE subtree AS (
              SELECT id, level FROM project_node WHERE id = #{rootId}
              UNION ALL
              SELECT n.id, n.level FROM project_node n
                JOIN subtree s ON n.parent_id = s.id
            )
            SELECT COALESCE(MAX(level), 0) FROM subtree
            """)
    int findMaxLevelInSubtree(@Param("rootId") long rootId);

    // ===== 插入 =====

    @Select("""
            INSERT INTO project_node
              (user_id, parent_id, name, level, node_type, sort_order)
            VALUES (#{userId}, #{parentId}, #{name}, #{level}, #{nodeType}, #{sortOrder})
            RETURNING id
            """)
    long insertWithoutEmbedding(@Param("userId") long userId,
                                @Param("parentId") Long parentId,
                                @Param("name") String name,
                                @Param("level") short level,
                                @Param("nodeType") String nodeType,
                                @Param("sortOrder") int sortOrder);

    @Select("""
            INSERT INTO project_node
              (user_id, parent_id, name, level, node_type, sort_order, embedding)
            VALUES (#{userId}, #{parentId}, #{name}, #{level}, #{nodeType}, #{sortOrder},
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

    /** name / sortOrder 用 COALESCE：null 表示不变。 */
    @Update("""
            UPDATE project_node SET
              name = COALESCE(#{name}, name),
              sort_order = COALESCE(#{sortOrder}, sort_order),
              updated_at = NOW()
            WHERE id = #{id}
            """)
    int updateBasic(@Param("id") long id,
                    @Param("name") String name,
                    @Param("sortOrder") Integer sortOrder);

    /** 跨父移动：一次 UPDATE 写 parent_id / level / node_type（项目树硬规则 level≥3→leaf）。 */
    @Update("""
            UPDATE project_node SET
              parent_id = #{parentId},
              level = #{level},
              node_type = #{nodeType},
              updated_at = NOW()
            WHERE id = #{id}
            """)
    int moveParent(@Param("id") long id,
                   @Param("parentId") Long parentId,
                   @Param("level") short level,
                   @Param("nodeType") String nodeType);

    @Update("UPDATE project_node SET sort_order = #{sortOrder}, updated_at = NOW() WHERE id = #{id}")
    int updateSortOrder(@Param("id") long id, @Param("sortOrder") int sortOrder);

    @Update("UPDATE project_node SET node_type = #{nodeType}, updated_at = NOW() WHERE id = #{id}")
    int updateNodeType(@Param("id") long id, @Param("nodeType") String nodeType);

    /**
     * 把以 rootId 为根的子树（不含 root 自身）所有节点 level += delta。
     * 与 S1 平行：跨父移动后整个子树 level 跟着平移，避免前端按 level 算缩进打扁。
     * 同时按"硬规则" level≥3 → 'leaf'，否则 'category'（项目树固定三层；
     *   实际操作中跨父挪动通常只发生在 level 1↔2 内部，level 3 已是叶子无子树）。
     */
    @Update("""
            WITH RECURSIVE descendants AS (
              SELECT id, level FROM project_node WHERE parent_id = #{rootId}
              UNION ALL
              SELECT n.id, n.level FROM project_node n
                JOIN descendants d ON n.parent_id = d.id
            )
            UPDATE project_node SET
              level = level + #{delta},
              node_type = CASE WHEN (level + #{delta}) >= 3 THEN 'leaf' ELSE 'category' END,
              updated_at = NOW()
            WHERE id IN (SELECT id FROM descendants)
            """)
    int shiftDescendantLevels(@Param("rootId") long rootId, @Param("delta") int delta);

    // ===== 删除 =====

    @Delete("""
            <script>
            DELETE FROM project_node WHERE id IN
            <foreach collection='ids' item='i' open='(' separator=',' close=')'>#{i}</foreach>
            </script>
            """)
    int deleteByIds(@Param("ids") List<Long> ids);
}
