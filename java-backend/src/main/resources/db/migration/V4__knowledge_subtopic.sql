-- V4: Learn 模块结构化重构
-- 1. 新建 knowledge_subtopic（子话题表）：替代原先单 Markdown 长文，每个 KP 多条子话题
-- 2. learn_chat 增加 quoted_subtopic_id：方便历史回看时高亮引用的子话题
-- 3. 彻底删除 knowledge_content（开发期，不迁移数据，重新由 LLM 生成）

CREATE TABLE knowledge_subtopic (
    id           BIGSERIAL PRIMARY KEY,
    kp_id        BIGINT     NOT NULL REFERENCES knowledge_node(id) ON DELETE CASCADE,
    title        TEXT       NOT NULL,
    body_md      TEXT       NOT NULL DEFAULT '',
    importance   SMALLINT   NOT NULL DEFAULT 3 CHECK (importance BETWEEN 1 AND 5),
    followups    JSONB      NOT NULL DEFAULT '[]'::jsonb,
    sort_order   INT        NOT NULL DEFAULT 0,
    source       TEXT       NOT NULL DEFAULT 'initial' CHECK (source IN ('initial', 'chat')),
    user_id      BIGINT     NOT NULL DEFAULT 1,
    created_at   TIMESTAMP  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_subtopic_kp ON knowledge_subtopic(kp_id, sort_order);

ALTER TABLE learn_chat
    ADD COLUMN quoted_subtopic_id BIGINT REFERENCES knowledge_subtopic(id) ON DELETE SET NULL;

DROP TABLE IF EXISTS knowledge_content;
