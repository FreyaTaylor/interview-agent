package com.interview.agent.interview.exp.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Optional;

/**
 * 面经来源表 {@code interview_exp_source} Mapper —— 频率去重的地基。
 *
 * <p>两级去重：{@link #findIdByHash} 精确（原样再传）、{@link #nearestDistance} 模糊（同文改写转发）。
 */
@Mapper
public interface InterviewExpSourceMapper {

    /** 按规范化文本 hash 精确查来源 id（命中即整篇重复）。 */
    @Select("SELECT id FROM interview_exp_source WHERE user_id = #{userId} AND text_hash = #{hash} LIMIT 1")
    Optional<Long> findIdByHash(@Param("userId") long userId, @Param("hash") String hash);

    /** 整篇 embedding 与历史来源的最近余弦距离（无历史/无向量 → empty）。 */
    @Select("""
            SELECT (embedding <=> #{vec}::vector)
            FROM interview_exp_source
            WHERE user_id = #{userId} AND embedding IS NOT NULL
            ORDER BY embedding <=> #{vec}::vector
            LIMIT 1
            """)
    Optional<Double> nearestDistance(@Param("userId") long userId, @Param("vec") String vec);

    @Select("""
            INSERT INTO interview_exp_source (user_id, raw_text, text_hash)
            VALUES (#{userId}, #{rawText}, #{hash})
            RETURNING id
            """)
    long insertWithoutEmbedding(@Param("userId") long userId,
                                @Param("rawText") String rawText,
                                @Param("hash") String hash);

    @Select("""
            INSERT INTO interview_exp_source (user_id, raw_text, text_hash, embedding)
            VALUES (#{userId}, #{rawText}, #{hash}, #{embeddingLiteral}::vector)
            RETURNING id
            """)
    long insertWithEmbedding(@Param("userId") long userId,
                             @Param("rawText") String rawText,
                             @Param("hash") String hash,
                             @Param("embeddingLiteral") String embeddingLiteral);
}
