package com.interview.agent.project.mapper;

import com.interview.agent.project.entity.Project;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Optional;

/**
 * 项目元数据 Mapper —— 项目已并入 tree_node：项目根 = tree_node(node_type='project')，
 * 元数据落 project_detail 侧表。project 的"id"即项目根节点 id（rootNodeId 与 id 同一）。
 */
@Mapper
public interface ProjectMapper {

    // tree_node(root) + project_detail 拼回 Project 实体；root_node_id 就是节点自身 id
    String SELECT_COLS = """
            SELECT t.id, t.user_id, t.name, d.description,
                   d.tech_stack::text AS tech_stack,
                   d.role, d.highlights, t.id AS root_node_id, t.created_at
            FROM tree_node t
            LEFT JOIN project_detail d ON d.node_id = t.id
            """;

    /** from-text：项目根节点已建，落 project_detail 元数据行；返回项目根节点 id（= 项目 id）。 */
    @Select("""
            INSERT INTO project_detail (node_id, description)
            VALUES (#{rootNodeId}, #{description})
            RETURNING node_id
            """)
    long insertReturningId(@Param("userId") long userId,
                           @Param("name") String name,
                           @Param("description") String description,
                           @Param("rootNodeId") long rootNodeId);

    @Select(SELECT_COLS + " WHERE t.id = #{id} AND t.node_type = 'project' AND t.user_id = #{userId}")
    Optional<Project> findById(@Param("id") long id, @Param("userId") long userId);

    /** 列出该 user 的全部项目（tree_node 项目根），按创建时间倒序。 */
    @Select(SELECT_COLS
            + " WHERE t.tree_kind = 'project' AND t.node_type = 'project' AND t.user_id = #{userId}"
            + " ORDER BY t.created_at DESC, t.id DESC")
    java.util.List<Project> listByUser(@Param("userId") long userId);
}
