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
            sort_order, is_user_created, created_at, updated_at
            """;

    // ===== 查询 =====

    @Select("SELECT " + COLS + " FROM knowledge_node ORDER BY level, sort_order, id")
    List<KnowledgeNode> findAllOrdered();

    @Select("SELECT " + COLS + " FROM knowledge_node WHERE id = #{id}")
    Optional<KnowledgeNode> findById(@Param("id") long id);

    @Select("SELECT id FROM knowledge_node WHERE parent_id = #{parentId}")
    List<Long> findChildIds(@Param("parentId") long parentId);

    @Select("SELECT EXISTS(SELECT 1 FROM knowledge_node WHERE parent_id = #{parentId})")
    boolean hasChildren(@Param("parentId") long parentId);

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

    @Update("UPDATE knowledge_node SET node_type = #{nodeType}, updated_at = NOW() WHERE id = #{id}")
    int updateNodeType(@Param("id") long id, @Param("nodeType") String nodeType);

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
