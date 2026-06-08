package com.interview.agent.project.mapper;

import com.interview.agent.project.entity.Project;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Optional;

/**
 * project 表 Mapper（项目元数据）。
 * <p>S6 仅需 INSERT；查询/更新留给 P1 拷打模块按需补。
 */
@Mapper
public interface ProjectMapper {

    /** S6 from-text：root_node 落库后同步插入 project 元数据行。 */
    @Select("""
            INSERT INTO project (user_id, name, description, root_node_id)
            VALUES (#{userId}, #{name}, #{description}, #{rootNodeId})
            RETURNING id
            """)
    long insertReturningId(@Param("userId") long userId,
                           @Param("name") String name,
                           @Param("description") String description,
                           @Param("rootNodeId") long rootNodeId);

    @Select("""
            SELECT id, user_id, name, description,
                   tech_stack::text AS tech_stack,
                   role, highlights, root_node_id, created_at
            FROM project WHERE id = #{id}
            """)
    Optional<Project> findById(@Param("id") long id);

    /** 列出该 user 的全部项目，按创建时间倒序（最新在前）。供 S7 拷打 projects-list 使用。 */
    @Select("""
            SELECT id, user_id, name, description,
                   tech_stack::text AS tech_stack,
                   role, highlights, root_node_id, created_at
            FROM project WHERE user_id = #{userId}
            ORDER BY created_at DESC, id DESC
            """)
    java.util.List<Project> listByUser(@Param("userId") long userId);
}
