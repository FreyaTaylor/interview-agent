package com.interview.agent.infra.db;

import com.fasterxml.jackson.core.type.TypeReference;
import com.interview.agent.common.JsonUtil;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;
import org.postgresql.util.PGobject;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * MyBatis &lt;-&gt; PostgreSQL JSONB 通用 TypeHandler。
 *
 * <p>读：JSONB 文本 → Jackson 反序列化为 Object（实际类型 = Map / List / String / Number / Boolean / null），
 * 等价于 Python dict / list / str。
 * <p>写：任意 Java 对象 → {@link JsonUtil#toJson} → PGobject(type=jsonb) 塞回参数。
 *
 * <p>使用：
 * <ul>
 *   <li>方式一：mybatis-config 全局 typeHandlers 注册（推荐，自动作用于所有 jdbcType=OTHER 的 JSONB 列）</li>
 *   <li>方式二：在具体 Mapper 上 @Result(typeHandler=JsonbTypeHandler.class)</li>
 * </ul>
 *
 * <p>下游使用者：S4 Learn（study_question.rubric_template / recommended_answer、knowledge_content.user_additions），
 * S3 Study（question_attempt.dialog / rubric_result / extension_qa），S7/S8 Interview（多字段 JSONB）。
 */
@MappedTypes(Object.class)
@MappedJdbcTypes(value = JdbcType.OTHER)
public class JsonbTypeHandler extends BaseTypeHandler<Object> {

    private static final TypeReference<Object> ANY = new TypeReference<>() {};

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, Object parameter, JdbcType jdbcType)
            throws SQLException {
        PGobject pg = new PGobject();
        pg.setType("jsonb");
        pg.setValue(JsonUtil.toJson(parameter));
        ps.setObject(i, pg);
    }

    @Override
    public Object getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return parse(rs.getString(columnName));
    }

    @Override
    public Object getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return parse(rs.getString(columnIndex));
    }

    @Override
    public Object getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return parse(cs.getString(columnIndex));
    }

    private Object parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JsonUtil.mapper().readValue(json, ANY);
        } catch (Exception e) {
            // JSONB 列被外部写脏的兜底：返回原始字符串，避免整次查询失败
            return json;
        }
    }
}
