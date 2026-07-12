-- V43: 统一节点树 tree_node 重构 —— S1 schema（drop 重建，不兼容历史）
-- 设计：local/java-backend-docs/design/2026-07-10-tree-node-unified.md
-- 背景：知识树(knowledge_node)/子话题(knowledge_subtopic)/问题(study_question)/项目树(project_node)+project
--   四张异构表合并为一张瘦骨架 tree_node + 类型侧表(subtopic_detail/question_detail/project_detail)。
-- 影响：本迁移 DROP 上述 5 表并重建骨架；引用它们的保留表(question_attempt/interview_*/user_answer_embedding/
--   project_session/project_user_profile) 因旧 id 失效 → 清空或 null 化后 FK 重指 tree_node。
-- 注意：不迁旧数据（开发期，知识/项目树需重新生成；作答/会话历史清空）。

-- =============================================================================
-- 1. 骨架树 tree_node（知识树 + 项目树同表，tree_kind 区分）
-- =============================================================================
CREATE TABLE tree_node (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT       NOT NULL DEFAULT 1,
    tree_kind   VARCHAR(16)  NOT NULL,               -- 'knowledge' | 'project'
    parent_id   BIGINT       REFERENCES tree_node(id) ON DELETE CASCADE,
    node_type   VARCHAR(20)  NOT NULL,               -- category|knowledge_point|subtopic|question / project|topic|question
    name        VARCHAR(500) NOT NULL,               -- 类目名/知识点名/子话题标题/题干
    level       SMALLINT     NOT NULL,
    sort_order  INTEGER      NOT NULL DEFAULT 0,
    interview_weight SMALLINT,                        -- 仅 knowledge_point 用（KP 星级）
    mastery_level    SMALLINT,                        -- knowledge_point 答题派生掌握度
    self_mastery     SMALLINT,                        -- knowledge_point 用户自评掌握度
    study_count      INTEGER  NOT NULL DEFAULT 0,      -- knowledge_point 累计答题次数
    is_user_created  BOOLEAN  NOT NULL DEFAULT FALSE,
    embedding   VECTOR(1024),                         -- 所有节点均保留（匹配/召回/去重）
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_tree_node_parent    ON tree_node(parent_id);
CREATE INDEX idx_tree_node_kind_type ON tree_node(tree_kind, node_type);

-- =============================================================================
-- 2. 子话题内容侧表
-- =============================================================================
CREATE TABLE subtopic_detail (
    node_id        BIGINT PRIMARY KEY REFERENCES tree_node(id) ON DELETE CASCADE,
    body_md        TEXT,                                       -- 懒生成，可空
    content_status VARCHAR(16) NOT NULL DEFAULT 'pending',     -- pending | ready
    mastery_level  SMALLINT                                    -- 子话题级掌握度，未答 null
);

-- =============================================================================
-- 3. 问题内容侧表（知识问题 + 项目问题共用）
-- =============================================================================
CREATE TABLE question_detail (
    node_id            BIGINT PRIMARY KEY REFERENCES tree_node(id) ON DELETE CASCADE,
    tier               VARCHAR(8) NOT NULL DEFAULT 'core' CHECK (tier IN ('core','ext')),
    rubric_template    JSONB,                                  -- 懒生成（首次答题 ensureRubric）
    recommended_answer JSONB
);
CREATE INDEX idx_question_detail_tier ON question_detail(tier);

-- =============================================================================
-- 4. 项目元数据侧表（project 表并入 tree_node 后，元数据落此）
-- =============================================================================
CREATE TABLE project_detail (
    node_id     BIGINT PRIMARY KEY REFERENCES tree_node(id) ON DELETE CASCADE,
    description TEXT,
    tech_stack  JSONB,
    role        VARCHAR(100),
    highlights  TEXT
);

-- =============================================================================
-- 5. question_attempt 去多态 —— 旧行失效，清空后改 FK
-- =============================================================================
DROP INDEX IF EXISTS idx_attempt_question;
TRUNCATE TABLE question_attempt;
ALTER TABLE question_attempt DROP COLUMN question_type;
CREATE INDEX idx_attempt_question
    ON question_attempt(question_id, status, finished_at DESC);

-- =============================================================================
-- 6. 保留表：清空 / null 化对旧表的引用（旧 id 已失效）
-- =============================================================================
-- 会话/画像强引用 project(id) NOT NULL → 直接清空
TRUNCATE TABLE project_session CASCADE;
TRUNCATE TABLE project_user_profile;
-- 面试记录保留，仅清空对已删节点的匹配（后续可重新匹配）
UPDATE interview_knowledge_question SET knowledge_node_id = NULL;
UPDATE interview_project_question   SET project_node_id   = NULL;
UPDATE user_answer_embedding        SET knowledge_point_id = NULL;

-- =============================================================================
-- 7. DROP 旧表（CASCADE 顺带删掉保留表上指向它们的旧 FK 约束）
-- =============================================================================
DROP TABLE IF EXISTS study_question      CASCADE;
DROP TABLE IF EXISTS knowledge_subtopic  CASCADE;
DROP TABLE IF EXISTS knowledge_node      CASCADE;
DROP TABLE IF EXISTS project             CASCADE;
DROP TABLE IF EXISTS project_node        CASCADE;

-- =============================================================================
-- 8. 保留表 FK 重指 tree_node
-- =============================================================================
ALTER TABLE question_attempt
    ADD CONSTRAINT fk_attempt_question
    FOREIGN KEY (question_id) REFERENCES tree_node(id) ON DELETE CASCADE;
ALTER TABLE interview_knowledge_question
    ADD CONSTRAINT fk_ikq_node
    FOREIGN KEY (knowledge_node_id) REFERENCES tree_node(id) ON DELETE SET NULL;
ALTER TABLE interview_project_question
    ADD CONSTRAINT fk_ipq_node
    FOREIGN KEY (project_node_id) REFERENCES tree_node(id) ON DELETE SET NULL;
ALTER TABLE user_answer_embedding
    ADD CONSTRAINT fk_uae_node
    FOREIGN KEY (knowledge_point_id) REFERENCES tree_node(id) ON DELETE SET NULL;
ALTER TABLE project_session
    ADD CONSTRAINT fk_session_project
    FOREIGN KEY (project_id) REFERENCES tree_node(id) ON DELETE CASCADE;
ALTER TABLE project_user_profile
    ADD CONSTRAINT fk_profile_project
    FOREIGN KEY (project_id) REFERENCES tree_node(id) ON DELETE CASCADE;
