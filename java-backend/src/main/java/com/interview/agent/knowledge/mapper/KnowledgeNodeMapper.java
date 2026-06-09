package com.interview.agent.knowledge.mapper;

import com.interview.agent.knowledge.entity.KnowledgeNode;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Optional;

/**
 * knowledge_node 表的 Mapper（MyBatis @ 注解）。
 *
 * SQL 与 Java 同文件：注解里写 SQL 文本块，IDE 折叠后即一个方法一行声明。
 * snake_case ↔ camelCase 由全局 mybatis.configuration.map-underscore-to-camel-case 处理；
 * Record 结果映射依赖 -parameters 编译参数 + 构造器形参名匹配（已在 pom 配置）。
 *
 * 共享给：admin（S1/S5 CRUD/树生成）、knowledge（S2 查询）、study（S3 推荐）、interview（S7/S8 匹配）。
 *
 * embedding 写入用 #{embeddingLiteral}::vector —— 参数仍然走预编译绑定，
 * 拼接后由 PG 转 vector 类型；EmbeddingService 已保证 literal 格式安全。
 */
@Mapper
public interface KnowledgeNodeMapper {

    String COLS = """
            id, parent_id, name, level, node_type, interview_weight,
            sort_order, is_user_created, mastery_level, study_count,
            created_at, updated_at
            """;

    // ===== 查询 =====

    @Select("SELECT " + COLS + " FROM knowledge_node ORDER BY level, sort_order, id")
    List<KnowledgeNode> findAllOrdered();

    @Select("SELECT " + COLS + " FROM knowledge_node WHERE id = #{id}")
    Optional<KnowledgeNode> findById(@Param("id") long id);

    @Select("SELECT id FROM knowledge_node WHERE parent_id = #{parentId}")
    List<Long> findChildIds(@Param("parentId") long parentId);

    /** 取所有根节点（parent_id IS NULL），用于 S5 树生成的同名/语义去重检查。 */
    @Select("SELECT " + COLS + " FROM knowledge_node WHERE parent_id IS NULL ORDER BY id")
    List<KnowledgeNode> findRoots();

    @Select("SELECT EXISTS(SELECT 1 FROM knowledge_node WHERE parent_id = #{parentId})")
    boolean hasChildren(@Param("parentId") long parentId);

    // ===== S8 面试匹配（embedding 召回 + 占位叶子 get_or_create）=====

    /** 按 level + name 取一个节点 id（复刻 get_or_create「未命名知识点」根的 filter_by）。 */
    @Select("SELECT id FROM knowledge_node WHERE level = #{level} AND name = #{name} ORDER BY id LIMIT 1")
    java.util.Optional<Long> findIdByLevelAndName(@Param("level") short level, @Param("name") String name);

    /** 在指定父下按 name 取子节点 id（复刻 _create_orphan_leaf 的同名复用）。 */
    @Select("SELECT id FROM knowledge_node WHERE parent_id = #{parentId} AND name = #{name} ORDER BY id LIMIT 1")
    java.util.Optional<Long> findChildIdByName(@Param("parentId") long parentId, @Param("name") String name);

    /**
     * pgvector 召回 top_k 最近叶子（仅 node_type='leaf' 且 embedding 非空）。
     * 复刻 embedding_match_skill：{@code (embedding <=> :vec) AS distance} 升序。
     */
    @Select("""
            SELECT id, name, (embedding <=> #{vec}::vector) AS distance
            FROM knowledge_node
            WHERE node_type = 'leaf' AND embedding IS NOT NULL
            ORDER BY embedding <=> #{vec}::vector
            LIMIT #{k}
            """)
    List<com.interview.agent.interview.matcher.NodeMatch> findNearestLeaves(
            @Param("vec") String vec, @Param("k") int k);

    /** interview_weight +1（上限 5）；复刻 update_knowledge_weights。仅当未达上限时生效。 */
    @Update("""
            UPDATE knowledge_node
            SET interview_weight = LEAST(5, interview_weight + 1), updated_at = NOW()
            WHERE id = #{id} AND interview_weight < 5
            """)
    int bumpInterviewWeight(@Param("id") long id);

    // ===== 插入 =====
    //
    // 用 @Select 承载 INSERT ... RETURNING id —— PG 驱动支持 executeQuery 取 ResultSet。
    // 比 @Options(useGeneratedKeys=true) + 可变 holder 干净（Record 无 setter）。

    @Select("""
            INSERT INTO knowledge_node
              (parent_id, name, level, node_type, interview_weight,
               sort_order, is_user_created)
            VALUES (#{parentId}, #{name}, #{level}, #{nodeType}, #{interviewWeight},
                    #{sortOrder}, #{isUserCreated})
            RETURNING id
            """)
    long insertWithoutEmbedding(@Param("parentId") Long parentId,
                                @Param("name") String name,
                                @Param("level") short level,
                                @Param("nodeType") String nodeType,
                                @Param("interviewWeight") short interviewWeight,
                                @Param("sortOrder") int sortOrder,
                                @Param("isUserCreated") boolean isUserCreated);

    @Select("""
            INSERT INTO knowledge_node
              (parent_id, name, level, node_type, interview_weight,
               sort_order, is_user_created, embedding)
            VALUES (#{parentId}, #{name}, #{level}, #{nodeType}, #{interviewWeight},
                    #{sortOrder}, #{isUserCreated}, #{embeddingLiteral}::vector)
            RETURNING id
            """)
    long insertWithEmbedding(@Param("parentId") Long parentId,
                             @Param("name") String name,
                             @Param("level") short level,
                             @Param("nodeType") String nodeType,
                             @Param("interviewWeight") short interviewWeight,
                             @Param("sortOrder") int sortOrder,
                             @Param("isUserCreated") boolean isUserCreated,
                             @Param("embeddingLiteral") String embeddingLiteral);

    // ===== 更新 =====

    /** name / interviewWeight / sortOrder 用 COALESCE：null 表示不变 */
    @Update("""
            UPDATE knowledge_node SET
              name = COALESCE(#{name}, name),
              interview_weight = COALESCE(#{interviewWeight}, interview_weight),
              sort_order = COALESCE(#{sortOrder}, sort_order),
              updated_at = NOW()
            WHERE id = #{id}
            """)
    int updateBasic(@Param("id") long id,
                    @Param("name") String name,
                    @Param("interviewWeight") Short interviewWeight,
                    @Param("sortOrder") Integer sortOrder);

    /** 跨父移动：一次 UPDATE 写 parent_id / level / node_type */
    @Update("""
            UPDATE knowledge_node SET
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

    @Update("UPDATE knowledge_node SET sort_order = #{sortOrder}, updated_at = NOW() WHERE id = #{id}")
    int updateSortOrder(@Param("id") long id, @Param("sortOrder") int sortOrder);

    /**
     * 把以 rootId 为根的子树（不含 root 自身）所有节点 level += delta。
     * 用于跨父亲拖动后，把整棵子树的 level 跟着平移，否则前端按 level 算缩进会"打扁"。
     * 用 PG 的 WITH RECURSIVE 一条 SQL 搞定，避免 Java 端 BFS。
     */
    @Update("""
            WITH RECURSIVE descendants AS (
              SELECT id FROM knowledge_node WHERE parent_id = #{rootId}
              UNION ALL
              SELECT n.id FROM knowledge_node n
                JOIN descendants d ON n.parent_id = d.id
            )
            UPDATE knowledge_node SET
              level = level + #{delta},
              updated_at = NOW()
            WHERE id IN (SELECT id FROM descendants)
            """)
    int shiftDescendantLevels(@Param("rootId") long rootId, @Param("delta") int delta);

    @Update("UPDATE knowledge_node SET node_type = #{nodeType}, updated_at = NOW() WHERE id = #{id}")
    int updateNodeType(@Param("id") long id, @Param("nodeType") String nodeType);

    /** S3 Study finish 钩子：覆盖最新掌握度并把 study_count 累加 1。 */
    @Update("""
            UPDATE knowledge_node
            SET mastery_level = #{mastery},
                study_count = study_count + 1,
                updated_at = NOW()
            WHERE id = #{id}
            """)
    int updateMastery(@Param("id") long id, @Param("mastery") Integer mastery);

    // ===== 删除 + FK 兜底 =====

    @Delete("""
            <script>
            DELETE FROM knowledge_node WHERE id IN
            <foreach collection='ids' item='i' open='(' separator=',' close=')'>#{i}</foreach>
            </script>
            """)
    int deleteByIds(@Param("ids") List<Long> ids);

    @Update("""
            <script>
            UPDATE interview_knowledge_question SET knowledge_node_id = NULL
            WHERE knowledge_node_id IN
            <foreach collection='ids' item='i' open='(' separator=',' close=')'>#{i}</foreach>
            </script>
            """)
    int nullOutInterviewKnowledgeRefs(@Param("ids") List<Long> ids);

    @Delete("""
            <script>
            DELETE FROM learn_chat WHERE knowledge_point_id IN
            <foreach collection='ids' item='i' open='(' separator=',' close=')'>#{i}</foreach>
            </script>
            """)
    int deleteLearnChat(@Param("ids") List<Long> ids);
}
