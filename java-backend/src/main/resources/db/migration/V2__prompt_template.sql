-- V2: Prompt 模板表
-- 设计动机：把 LLM prompt 内容从 classpath txt 迁移到 DB，便于运营/管理员热更新。
-- 启动时 PromptSeeder 会扫描 classpath:prompts/**/*.txt，若 key 不存在则插入；
-- 已存在则保留 DB 内容（避免覆盖运营编辑）。
-- key 命名：与 classpath 路径一致（去掉 .txt 后缀），如 "learn/content-gen"、"tree/generate"。

CREATE TABLE prompt_template (
    id          BIGSERIAL PRIMARY KEY,
    key         VARCHAR(128) NOT NULL UNIQUE,
    content     TEXT         NOT NULL,
    description VARCHAR(255),
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE prompt_template IS 'LLM Prompt 模板（含 {var} 占位符），运行时按 key 查询并渲染';
COMMENT ON COLUMN prompt_template.key IS '模板 key，对应 classpath:prompts/{key}.txt 路径';
COMMENT ON COLUMN prompt_template.content IS '模板内容，占位符格式 {var_name}（snake_case）';
COMMENT ON COLUMN prompt_template.description IS '模板用途说明（可选）';
