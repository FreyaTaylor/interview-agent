-- V58: 新增 interview_question_kp_link —— 面试真题 ↔ 知识点 的 N:N 关联表（三模块解耦重构 P1）
-- 背景：三模块解耦（见 design/2026-07-13-三模块解耦重构.md）。面试真题「属于面试模块」，
--        知识点「查看相关面试题」用只读关联（连线，非把真题搬进学习结构）。
-- 现状：interview_knowledge_question.knowledge_node_id 列存在但从未被填充（stub），且是 1:1，
--        无法承载"一道真题召回相关的几个知识点"。
-- 设计：独立关联表，锚定面试模块自己的真题 id（interview_knowledge_question.id），支持 N:N；
--        带 knowledge_point_name 快照，知识点被删（SET NULL）也保留"当时考了哪个点"这一历史事实。
-- Strangler：本迁移只加新表，不动任何现有逻辑。

CREATE TABLE interview_question_kp_link (
    id                             BIGSERIAL PRIMARY KEY,
    user_id                        BIGINT       NOT NULL DEFAULT 1,
    interview_knowledge_question_id BIGINT      NOT NULL,
    knowledge_point_id             BIGINT,                       -- 知识点被删后置 NULL，保留快照
    knowledge_point_name           VARCHAR(200) NOT NULL,        -- 快照：关联当时的知识点名
    source                         VARCHAR(16)  NOT NULL DEFAULT 'recall',  -- recall(语义召回) | manual(人工)
    similarity                     REAL,                         -- 召回相似度（manual 时可空）
    created_at                     TIMESTAMP    NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_iqkl_question FOREIGN KEY (interview_knowledge_question_id)
        REFERENCES interview_knowledge_question(id) ON DELETE CASCADE,
    CONSTRAINT fk_iqkl_kp FOREIGN KEY (knowledge_point_id)
        REFERENCES tree_node(id) ON DELETE SET NULL,
    CONSTRAINT chk_iqkl_source CHECK (source IN ('recall', 'manual')),
    -- 同一真题↔同一知识点只留一条（upsert 幂等的依据）
    CONSTRAINT uq_iqkl_pair UNIQUE (interview_knowledge_question_id, knowledge_point_id)
);

-- 「知识点查相关真题」正向查询
CREATE INDEX idx_iqkl_kp ON interview_question_kp_link(knowledge_point_id);
-- 「真题查关联知识点」反向查询
CREATE INDEX idx_iqkl_question ON interview_question_kp_link(interview_knowledge_question_id);
