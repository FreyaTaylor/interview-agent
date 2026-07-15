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
 * 知识树节点的 Mapper（MyBatis @ 注解）—— 统一节点树 {@code tree_node} 中 {@code tree_kind='knowledge'} 的部分。
 *
 * SQL 与 Java 同文件：注解里写 SQL 文本块，IDE 折叠后即一个方法一行声明。
 * snake_case ↔ camelCase 由全局 mybatis.configuration.map-underscore-to-camel-case 处理；
 * Record 结果映射依赖 -parameters 编译参数 + 构造器形参名匹配（已在 pom 配置）。
 *
 * 共享给：admin（CRUD/树生成）、knowledge（查询）、study（掌握度写回）、interview（匹配）。
 *
 * embedding 写入用 #{embeddingLiteral}::vector —— 参数仍然走预编译绑定，
 * 拼接后由 PG 转 vector 类型；EmbeddingService 已保证 literal 格式安全。
 *
 * <p>tree_node 结构与内容分离：本 Mapper 只管知识树骨架（类目/知识点节点）；
 * 子话题正文在 subtopic_detail、问题内容在 question_detail（各自 Mapper）。
 */
@Mapper
public interface KnowledgeNodeMapper {

    String COLS = """
            id, parent_id, name, level, node_type, interview_weight,
            sort_order, mastery_level, study_count,
            self_mastery, created_at, updated_at
            """;

    // ===== 查询 =====

    @Select("SELECT " + COLS + " FROM tree_node WHERE tree_kind = 'knowledge'"
            + " AND node_type IN ('category', 'knowledge_point')"
            + " AND user_id = #{userId} ORDER BY level, sort_order, id")
    List<KnowledgeNode> findAllOrdered(@Param("userId") long userId);

    /**
     * 全量知识树（管理视图）：不过滤 node_type，问题节点 LEFT JOIN question_detail 带出 tier/source。
     * interview_weight/mastery_level 对子话题/问题为 NULL，用 COALESCE 归零避免 short 主类型映射 NPE。
     */
    @Select("""
            SELECT t.id, t.parent_id, t.name, t.level, t.node_type,
                   COALESCE(t.interview_weight, 0) AS interview_weight,
                   t.sort_order,
                   COALESCE(t.mastery_level, 0) AS mastery_level,
                   COALESCE(t.self_mastery, 0) AS self_mastery,
                   q.tier, q.source
            FROM tree_node t
            LEFT JOIN question_detail q ON q.node_id = t.id AND t.node_type = 'question'
            WHERE t.tree_kind = 'knowledge' AND t.user_id = #{userId}
            ORDER BY t.level, t.sort_order, t.id
            """)
    List<com.interview.agent.knowledge.dto.KnowledgeFullRow> findFullTree(@Param("userId") long userId);

    @Select("SELECT " + COLS + " FROM tree_node WHERE id = #{id} AND user_id = #{userId}")
    Optional<KnowledgeNode> findById(@Param("id") long id, @Param("userId") long userId);

    @Select("SELECT id FROM tree_node WHERE parent_id = #{parentId} AND user_id = #{userId}")
    List<Long> findChildIds(@Param("parentId") long parentId, @Param("userId") long userId);

    /** 取当前用户的所有知识树根节点（parent_id IS NULL），用于树生成的同名/语义去重检查。 */
    @Select("SELECT " + COLS + " FROM tree_node WHERE tree_kind = 'knowledge' AND parent_id IS NULL AND user_id = #{userId} ORDER BY id")
    List<KnowledgeNode> findRoots(@Param("userId") long userId);

    @Select("SELECT EXISTS(SELECT 1 FROM tree_node WHERE parent_id = #{parentId} AND user_id = #{userId})")
    boolean hasChildren(@Param("parentId") long parentId, @Param("userId") long userId);

    // ===== 面试匹配（embedding 召回 + 占位知识点 get_or_create）=====

    /** 按 level + name 取当前用户的一个知识节点 id（复刻 get_or_create「未命名知识点」根的 filter_by）。 */
    @Select("SELECT id FROM tree_node WHERE tree_kind = 'knowledge' AND level = #{level} AND name = #{name} AND user_id = #{userId} ORDER BY id LIMIT 1")
    java.util.Optional<Long> findIdByLevelAndName(@Param("level") short level, @Param("name") String name,
                                                 @Param("userId") long userId);

    /** 在指定父下按 name 取子节点 id（复刻 _create_orphan_leaf 的同名复用）。 */
    @Select("SELECT id FROM tree_node WHERE parent_id = #{parentId} AND name = #{name} AND user_id = #{userId} ORDER BY id LIMIT 1")
    java.util.Optional<Long> findChildIdByName(@Param("parentId") long parentId, @Param("name") String name,
                                               @Param("userId") long userId);

    /**
     * pgvector 召回 top_k 最近知识点（仅 node_type='knowledge_point' 且 embedding 非空）。
     * 复刻 embedding_match_skill：{@code (embedding <=> :vec) AS distance} 升序。
     * <p>额外 LEFT JOIN 父节点取 {@code path}（父分类名），供 rerank 时给 LLM 判"域"，防跨域错配。
     */
    @Select("""
            SELECT n.id AS id, n.name AS name, p.name AS path,
                   (n.embedding <=> #{vec}::vector) AS distance
            FROM tree_node n
            LEFT JOIN tree_node p ON p.id = n.parent_id
            WHERE n.tree_kind = 'knowledge' AND n.node_type = 'knowledge_point'
              AND n.embedding IS NOT NULL AND n.user_id = #{userId}
            ORDER BY n.embedding <=> #{vec}::vector
            LIMIT #{k}
            """)
    List<com.interview.agent.interview.matcher.NodeMatch> findNearestLeaves(
            @Param("userId") long userId, @Param("vec") String vec, @Param("k") int k);

    /** interview_weight +1（上限 5）；复刻 update_knowledge_weights。仅当未达上限且属于当前用户时生效。 */
    @Update("""
            UPDATE tree_node
            SET interview_weight = LEAST(5, interview_weight + 1), updated_at = NOW()
            WHERE id = #{id} AND user_id = #{userId} AND interview_weight < 5
            """)
    int bumpInterviewWeight(@Param("id") long id, @Param("userId") long userId);

    // ===== 插入 =====
    //
    // 用 @Select 承载 INSERT ... RETURNING id —— PG 驱动支持 executeQuery 取 ResultSet。
    // 比 @Options(useGeneratedKeys=true) + 可变 holder 干净（Record 无 setter）。

    @Select("""
            INSERT INTO tree_node
              (tree_kind, user_id, parent_id, name, level, node_type, interview_weight,
               sort_order)
            VALUES ('knowledge', #{userId}, #{parentId}, #{name}, #{level}, #{nodeType}, #{interviewWeight},
                    #{sortOrder})
            RETURNING id
            """)
    long insertWithoutEmbedding(@Param("userId") long userId,
                                @Param("parentId") Long parentId,
                                @Param("name") String name,
                                @Param("level") short level,
                                @Param("nodeType") String nodeType,
                                @Param("interviewWeight") short interviewWeight,
                                @Param("sortOrder") int sortOrder);

    @Select("""
            INSERT INTO tree_node
              (tree_kind, user_id, parent_id, name, level, node_type, interview_weight,
               sort_order, embedding)
            VALUES ('knowledge', #{userId}, #{parentId}, #{name}, #{level}, #{nodeType}, #{interviewWeight},
                    #{sortOrder}, #{embeddingLiteral}::vector)
            RETURNING id
            """)
    long insertWithEmbedding(@Param("userId") long userId,
                             @Param("parentId") Long parentId,
                             @Param("name") String name,
                             @Param("level") short level,
                             @Param("nodeType") String nodeType,
                             @Param("interviewWeight") short interviewWeight,
                             @Param("sortOrder") int sortOrder,
                             @Param("embeddingLiteral") String embeddingLiteral);

    // ===== 更新 =====

    /** name / interviewWeight / sortOrder 用 COALESCE：null 表示不变；仅限当前用户自己的节点 */
    @Update("""
            UPDATE tree_node SET
              name = COALESCE(#{name}, name),
              interview_weight = COALESCE(#{interviewWeight}, interview_weight),
              sort_order = COALESCE(#{sortOrder}, sort_order),
              updated_at = NOW()
            WHERE id = #{id} AND user_id = #{userId}
            """)
    int updateBasic(@Param("id") long id,
                    @Param("userId") long userId,
                    @Param("name") String name,
                    @Param("interviewWeight") Short interviewWeight,
                    @Param("sortOrder") Integer sortOrder);

    /** 跨父移动：一次 UPDATE 写 parent_id / level / node_type（仅限当前用户） */
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
     * 把以 rootId 为根的子树（不含 root 自身）所有节点 level += delta。
     * 用于跨父亲拖动后，把整棵子树的 level 跟着平移，否则前端按 level 算缩进会"打扁"。
     * 用 PG 的 WITH RECURSIVE 一条 SQL 搞定，避免 Java 端 BFS。
     */
    @Update("""
            WITH RECURSIVE descendants AS (
              SELECT id FROM tree_node WHERE parent_id = #{rootId}
              UNION ALL
              SELECT n.id FROM tree_node n
                JOIN descendants d ON n.parent_id = d.id
            )
            UPDATE tree_node SET
              level = level + #{delta},
              updated_at = NOW()
            WHERE id IN (SELECT id FROM descendants) AND user_id = #{userId}
            """)
    int shiftDescendantLevels(@Param("rootId") long rootId, @Param("userId") long userId, @Param("delta") int delta);

    @Update("UPDATE tree_node SET node_type = #{nodeType}, updated_at = NOW() WHERE id = #{id} AND user_id = #{userId}")
    int updateNodeType(@Param("id") long id, @Param("userId") long userId, @Param("nodeType") String nodeType);

    /** Study finish 钩子：覆盖最新掌握度并把 study_count 累加 1（仅限当前用户）。 */
    @Update("""
            UPDATE tree_node
            SET mastery_level = #{mastery},
                study_count = study_count + 1,
                updated_at = NOW()
            WHERE id = #{id} AND user_id = #{userId}
            """)
    int updateMastery(@Param("id") long id, @Param("userId") long userId, @Param("mastery") Integer mastery);

    /** 学习页手动设置/清除自评掌握度（selfMastery 为 null 表示清除；仅限当前用户）。 */
    @Update("UPDATE tree_node SET self_mastery = #{selfMastery}, updated_at = NOW() WHERE id = #{id} AND user_id = #{userId}")
    int updateSelfMastery(@Param("id") long id, @Param("userId") long userId, @Param("selfMastery") Short selfMastery);

    // ===== 删除 + FK 兜底 =====

    @Delete("""
            <script>
            DELETE FROM tree_node WHERE user_id = #{userId} AND id IN
            <foreach collection='ids' item='i' open='(' separator=',' close=')'>#{i}</foreach>
            </script>
            """)
    int deleteByIds(@Param("userId") long userId, @Param("ids") List<Long> ids);

    @Update("""
            <script>
            UPDATE interview_knowledge_question SET knowledge_node_id = NULL
            WHERE knowledge_node_id IN
            <foreach collection='ids' item='i' open='(' separator=',' close=')'>#{i}</foreach>
            </script>
            """)
    int nullOutInterviewKnowledgeRefs(@Param("ids") List<Long> ids);
}
