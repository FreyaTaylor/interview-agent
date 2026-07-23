-- V70: 面经解析模块 —— 来源表 + 问题↔来源关联表
-- 背景：新增「面经解析」功能——把别人整理好的面经文本/图片，解析为规整问题清单（按知识域分类 + 语义去重 + 出现频率）。
-- 设计：local/java-backend-docs/design/2026-07-23-interview-experience-parse.md
-- 复用：面经树复用统一表 tree_node（tree_kind='interview_exp'；node_type=domain(level1)/question(level2)），
--       本迁移仅新增两张辅助表，无需改 tree_node（tree_kind 为变长 VARCHAR 无枚举约束）。

-- =============================================================================
-- 1. 面经来源表 —— 每提交一篇「新」面经落一行（频率去重的地基）
--    text_hash：SHA-256(规范化文本)，精确去重（原样再传）
--    embedding：整篇向量，模糊去重（同文改写转发）
-- =============================================================================
CREATE TABLE interview_exp_source (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT       NOT NULL DEFAULT 1,
    raw_text    TEXT         NOT NULL,
    text_hash   VARCHAR(64)  NOT NULL,
    embedding   VECTOR(1024),
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);
-- 同一用户内 hash 唯一 → 原样重复提交被 hash 精确拦
CREATE UNIQUE INDEX uq_interview_exp_source_hash ON interview_exp_source(user_id, text_hash);

-- =============================================================================
-- 2. 问题 ↔ 来源 关联表 —— 出现频率 = COUNT(link)
--    唯一约束 (question_node_id, source_id)：同一来源对同一问题最多贡献一次（DB 层防灌水）
-- =============================================================================
CREATE TABLE question_source_link (
    id                BIGSERIAL PRIMARY KEY,
    question_node_id  BIGINT    NOT NULL REFERENCES tree_node(id) ON DELETE CASCADE,
    source_id         BIGINT    NOT NULL REFERENCES interview_exp_source(id) ON DELETE CASCADE,
    created_at        TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX uq_question_source ON question_source_link(question_node_id, source_id);
CREATE INDEX idx_qsl_question ON question_source_link(question_node_id);

-- =============================================================================
-- 3. 面经树召回优化 —— 域内 embedding 去重查询走此部分索引
-- =============================================================================
CREATE INDEX idx_tree_node_interview_exp ON tree_node(user_id, parent_id)
    WHERE tree_kind = 'interview_exp';
