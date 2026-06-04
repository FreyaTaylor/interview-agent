package com.interview.agent.user.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Optional;

/**
 * user 表的 Mapper（最小实现，仅 S5 树生成读 profile_text 用）。
 *
 * <p>完整 User 模块（S9）会扩充：登录 / 注册 / profile 增删改。
 * 这里先开一个口子，避免 S5 阻塞等 S9。
 *
 * <p>SQL 注意 <code>"user"</code> 必须加双引号 —— user 是 PG 保留字，
 * 不带引号会被解析为当前会话用户。
 */
@Mapper
public interface UserMapper {

    /**
     * 取指定 user 的画像文本（profile_text 列）。
     *
     * @param id user.id；一期固定传 1
     * @return Optional<画像文本>；user 不存在或 profile_text 为 NULL → Optional.empty()
     */
    @Select("SELECT profile_text FROM \"user\" WHERE id = #{id}")
    Optional<String> findProfileText(@Param("id") long id);
}
