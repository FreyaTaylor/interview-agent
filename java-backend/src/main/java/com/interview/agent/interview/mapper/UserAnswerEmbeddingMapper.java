package com.interview.agent.interview.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * user_answer_embedding 表写入 —— 复刻 Python store_answer_embeddings。
 *
 * <p>embedding 可空：DashScope 调用失败时退化为不带向量写入（与知识树节点落库一致）。
 * 用 {@code #{embeddingLiteral}::vector} 占位，由 PgVector.toLiteral 生成安全字面量。
 */
@Mapper
public interface UserAnswerEmbeddingMapper {

    @Select("""
            INSERT INTO user_answer_embedding
              (knowledge_point_id, source, knowledge_point_name, question_text, answer_text, embedding, score)
            VALUES
              (#{knowledgePointId}, #{source}, #{knowledgePointName}, #{questionText}, #{answerText},
               #{embeddingLiteral}::vector, #{score})
            RETURNING id
            """)
    long insertWithEmbedding(@Param("knowledgePointId") Long knowledgePointId,
                             @Param("source") String source,
                             @Param("knowledgePointName") String knowledgePointName,
                             @Param("questionText") String questionText,
                             @Param("answerText") String answerText,
                             @Param("embeddingLiteral") String embeddingLiteral,
                             @Param("score") Integer score);

    @Select("""
            INSERT INTO user_answer_embedding
              (knowledge_point_id, source, knowledge_point_name, question_text, answer_text, score)
            VALUES
              (#{knowledgePointId}, #{source}, #{knowledgePointName}, #{questionText}, #{answerText}, #{score})
            RETURNING id
            """)
    long insertWithoutEmbedding(@Param("knowledgePointId") Long knowledgePointId,
                                @Param("source") String source,
                                @Param("knowledgePointName") String knowledgePointName,
                                @Param("questionText") String questionText,
                                @Param("answerText") String answerText,
                                @Param("score") Integer score);
}
