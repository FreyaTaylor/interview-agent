-- 项目答题画像表：增量累积用户在每个项目上的答题统计、暴露关键词，供 Agent 出题/追问参考
CREATE TABLE IF NOT EXISTS project_user_profile (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL DEFAULT 1,
    project_id BIGINT NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    topic_stats JSONB NOT NULL DEFAULT '{}'::jsonb,
    exposed_keywords JSONB NOT NULL DEFAULT '[]'::jsonb,
    version INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_project_user_profile_proj_user UNIQUE (project_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_project_user_profile_project ON project_user_profile (project_id);
