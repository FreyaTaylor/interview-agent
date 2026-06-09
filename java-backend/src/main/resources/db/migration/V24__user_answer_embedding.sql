-- =============================================================================
-- V24: user_answer_embedding —— 用户回答向量（Agent 长期记忆）
--
-- 忠实复刻 Python backend/models/interview.py::UserAnswerEmbedding：
--   finalize 时把 knowledge/project 类的用户回答向量化落库，供后续召回/推荐。
-- 与其它 interview 子表一致，仅保留 created_at（本表 insert-only，不更新）。
-- =============================================================================

CREATE TABLE user_answer_embedding (
    id                   BIGSERIAL PRIMARY KEY,
    user_id              BIGINT       NOT NULL DEFAULT 1,
    knowledge_point_id   BIGINT REFERENCES knowledge_node(id),  -- 可空：未匹配到知识节点
    source               VARCHAR(20)  NOT NULL,                 -- 'interview' | 'study'
    knowledge_point_name VARCHAR(200) NOT NULL,
    question_text        TEXT         NOT NULL,                 -- 面试官的问题（" | " 拼接）
    answer_text          TEXT         NOT NULL,                 -- 用户的回答
    embedding            VECTOR(1024),                          -- DashScope embedding，失败可空
    score                SMALLINT,                              -- 当次得分（仅 knowledge 有）
    created_at           TIMESTAMP    NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_uae_kp ON user_answer_embedding(knowledge_point_id);
