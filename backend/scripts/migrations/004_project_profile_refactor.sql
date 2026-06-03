-- 项目画像重构 + 新建待深入问题队列表
-- 1) project_user_profile：删旧字段（topic_stats / exposed_keywords），
--    加新字段（project_facts / weak_points）
-- 2) 新建 project_question_backlog 表

-- ===== Step 1：project_user_profile 字段替换 =====
ALTER TABLE project_user_profile DROP COLUMN IF EXISTS topic_stats;
ALTER TABLE project_user_profile DROP COLUMN IF EXISTS exposed_keywords;

ALTER TABLE project_user_profile
    ADD COLUMN IF NOT EXISTS project_facts JSONB NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE project_user_profile
    ADD COLUMN IF NOT EXISTS weak_points JSONB NOT NULL DEFAULT '[]'::jsonb;

-- ===== Step 2：project_question_backlog =====
CREATE TABLE IF NOT EXISTS project_question_backlog (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL DEFAULT 1,
    project_id BIGINT NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    dimension VARCHAR(100),
    question TEXT NOT NULL,
    source VARCHAR(20) NOT NULL DEFAULT 'extract',
    source_session_id BIGINT,
    source_round INTEGER,
    status VARCHAR(20) NOT NULL DEFAULT 'pending',
    consumed_at TIMESTAMP,
    consumed_session_id BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- 消费时按 (project_id, status, dimension, created_at) 过滤
CREATE INDEX IF NOT EXISTS idx_backlog_consume
    ON project_question_backlog (project_id, status, dimension, created_at DESC);
