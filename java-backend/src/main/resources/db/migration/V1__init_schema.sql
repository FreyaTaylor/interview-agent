-- =============================================================================
-- V1 初始化 schema —— 与 Python 端语义对齐
--
-- 字段约定（来自 backend/models/base.py）：
--   * id           BIGSERIAL PRIMARY KEY
--   * created_at   TIMESTAMP NOT NULL DEFAULT NOW()
--   * updated_at   TIMESTAMP NOT NULL DEFAULT NOW()（对应模型显式声明的字段）
--   * user_id      BIGINT NOT NULL DEFAULT 1
--   * embedding    VECTOR(1024)（pgvector，DashScope text-embedding-v3 维度）
--
-- 命名：snake_case 单数表名；JSONB 用于半结构化字段。
-- =============================================================================

CREATE EXTENSION IF NOT EXISTS vector;

-- -----------------------------------------------------------------------------
-- 1. user —— 用户（GitHub OAuth）
-- -----------------------------------------------------------------------------
CREATE TABLE "user" (
    id           BIGSERIAL PRIMARY KEY,
    username     VARCHAR(100) NOT NULL UNIQUE,
    password     VARCHAR(200) NOT NULL DEFAULT '',
    role         VARCHAR(20)  NOT NULL DEFAULT 'user',
    profile_text TEXT,
    github_id    BIGINT UNIQUE,
    github_login VARCHAR(100),
    avatar_url   VARCHAR(500),
    created_at   TIMESTAMP NOT NULL DEFAULT NOW()
);

-- -----------------------------------------------------------------------------
-- 2. knowledge_node —— 知识树（邻接表，三层：1=一级 / 2=二级 / 3=叶子）
-- -----------------------------------------------------------------------------
CREATE TABLE knowledge_node (
    id               BIGSERIAL PRIMARY KEY,
    parent_id        BIGINT REFERENCES knowledge_node(id),
    name             VARCHAR(200) NOT NULL,
    level            SMALLINT     NOT NULL,
    node_type        VARCHAR(20)  NOT NULL,      -- 'category' | 'leaf'
    interview_weight SMALLINT     NOT NULL DEFAULT 3,
    sort_order       INTEGER      NOT NULL DEFAULT 0,
    is_user_created  BOOLEAN      NOT NULL DEFAULT FALSE,
    embedding        VECTOR(1024),
    created_at       TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMP    NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_knowledge_node_parent ON knowledge_node(parent_id);
CREATE INDEX idx_knowledge_node_level  ON knowledge_node(level);

-- -----------------------------------------------------------------------------
-- 3. project_node —— 项目树（三层：1=项目 / 2=话题 / 3=问题）
-- -----------------------------------------------------------------------------
CREATE TABLE project_node (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT      NOT NULL DEFAULT 1,
    parent_id  BIGINT REFERENCES project_node(id),
    name       VARCHAR(500) NOT NULL,
    level      SMALLINT    NOT NULL,
    node_type  VARCHAR(20) NOT NULL,            -- 'category' | 'leaf'
    sort_order INTEGER     NOT NULL DEFAULT 0,
    embedding  VECTOR(1024),
    created_at TIMESTAMP   NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP   NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_project_node_parent ON project_node(parent_id);
CREATE INDEX idx_project_node_user   ON project_node(user_id);

-- -----------------------------------------------------------------------------
-- 4. project —— 项目元数据
-- root_node_id 引用 project_node(id)，删树时 SET NULL（不影响会话历史）
-- -----------------------------------------------------------------------------
CREATE TABLE project (
    id           BIGSERIAL PRIMARY KEY,
    user_id      BIGINT      NOT NULL DEFAULT 1,
    name         VARCHAR(200) NOT NULL,
    description  TEXT,
    tech_stack   JSONB,
    role         VARCHAR(100),
    highlights   TEXT,
    root_node_id BIGINT REFERENCES project_node(id) ON DELETE SET NULL,
    created_at   TIMESTAMP   NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_project_user ON project(user_id);

-- -----------------------------------------------------------------------------
-- 5. project_session —— 项目拷打会话
-- -----------------------------------------------------------------------------
CREATE TABLE project_session (
    id                  BIGSERIAL PRIMARY KEY,
    user_id             BIGINT      NOT NULL DEFAULT 1,
    project_id          BIGINT      NOT NULL REFERENCES project(id),
    status              VARCHAR(20) NOT NULL DEFAULT 'active',
    current_topic       VARCHAR(200),
    current_question    TEXT,
    current_rubric      JSONB,
    learning_summaries  JSONB,
    pending_questions   JSONB,
    readiness_score     SMALLINT,
    follow_up_count     INTEGER     NOT NULL DEFAULT 0,
    created_at          TIMESTAMP   NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_project_session_project ON project_session(project_id);

-- -----------------------------------------------------------------------------
-- 6. project_session_message —— 拷打消息（完整审计日志）
-- -----------------------------------------------------------------------------
CREATE TABLE project_session_message (
    id           BIGSERIAL PRIMARY KEY,
    session_id   BIGINT      NOT NULL REFERENCES project_session(id) ON DELETE CASCADE,
    message_type VARCHAR(20) NOT NULL,           -- question/answer/scoring/follow_up/summary
    content      TEXT,
    extra        JSONB,
    created_at   TIMESTAMP   NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_project_session_message_session ON project_session_message(session_id);

-- -----------------------------------------------------------------------------
-- 7. project_user_profile —— 用户在某项目上的答题画像（乐观锁）
-- -----------------------------------------------------------------------------
CREATE TABLE project_user_profile (
    id            BIGSERIAL PRIMARY KEY,
    user_id       BIGINT  NOT NULL DEFAULT 1,
    project_id    BIGINT  NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    project_facts JSONB   DEFAULT '[]'::jsonb,
    weak_points   JSONB   DEFAULT '[]'::jsonb,
    version       INTEGER NOT NULL DEFAULT 0,
    created_at    TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_project_user_profile_proj_user UNIQUE (project_id, user_id)
);

-- -----------------------------------------------------------------------------
-- 8. study_question —— 知识点下的预生成题目
-- -----------------------------------------------------------------------------
CREATE TABLE study_question (
    id                  BIGSERIAL PRIMARY KEY,
    user_id             BIGINT  NOT NULL DEFAULT 1,
    knowledge_point_id  BIGINT  NOT NULL REFERENCES knowledge_node(id) ON DELETE CASCADE,
    content             TEXT    NOT NULL,
    rubric_template     JSONB   DEFAULT '[]'::jsonb,
    recommended_answer  JSONB,
    sort_order          INTEGER NOT NULL DEFAULT 0,
    created_at          TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_study_question_kp ON study_question(knowledge_point_id);

-- -----------------------------------------------------------------------------
-- 9. question_attempt —— 一次完整作答（多态：study | project）
-- question_id 是逻辑外键（study_question.id 或 project_node.id），由应用层保证完整性
-- -----------------------------------------------------------------------------
CREATE TABLE question_attempt (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT      NOT NULL DEFAULT 1,
    question_type   VARCHAR(20) NOT NULL,        -- 'study' | 'project'
    question_id     BIGINT      NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'in_progress',
    final_score     SMALLINT,
    rubric_result   JSONB,
    overall_summary TEXT,
    design_issues   JSONB,
    extension_qa    JSONB,
    dialog          JSONB       NOT NULL DEFAULT '[]'::jsonb,
    follow_up_count SMALLINT    NOT NULL DEFAULT 0,
    finished_at     TIMESTAMP,
    created_at      TIMESTAMP   NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_attempt_question_finished
    ON question_attempt(question_type, question_id, status, finished_at DESC);
CREATE INDEX idx_attempt_user ON question_attempt(user_id);

-- -----------------------------------------------------------------------------
-- 10. knowledge_content —— 知识点讲解长文（每个知识点唯一）
-- -----------------------------------------------------------------------------
CREATE TABLE knowledge_content (
    id                 BIGSERIAL PRIMARY KEY,
    knowledge_point_id BIGINT NOT NULL UNIQUE REFERENCES knowledge_node(id),
    user_id            BIGINT NOT NULL DEFAULT 1,
    content            TEXT   NOT NULL,
    user_additions     JSONB,
    created_at         TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMP NOT NULL DEFAULT NOW()
);

-- -----------------------------------------------------------------------------
-- 11. learn_chat —— 学习探索对话
-- -----------------------------------------------------------------------------
CREATE TABLE learn_chat (
    id                 BIGSERIAL PRIMARY KEY,
    knowledge_point_id BIGINT      NOT NULL REFERENCES knowledge_node(id),
    user_id            BIGINT      NOT NULL DEFAULT 1,
    role               VARCHAR(10) NOT NULL,     -- 'user' | 'assistant'
    content            TEXT        NOT NULL,
    quoted_text        TEXT,
    created_at         TIMESTAMP   NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_learn_chat_kp ON learn_chat(knowledge_point_id, created_at);

-- -----------------------------------------------------------------------------
-- 12. interview_record —— 面试文本记录
-- -----------------------------------------------------------------------------
CREATE TABLE interview_record (
    id               BIGSERIAL PRIMARY KEY,
    raw_text         TEXT NOT NULL,
    company          VARCHAR(200),
    position         VARCHAR(200),
    text_hash        VARCHAR(64),
    avg_score        SMALLINT,
    pass_estimate    VARCHAR(20),
    parsed_questions JSONB,
    cluster_result   JSONB,
    summary_report   TEXT,
    draft_turns      JSONB,
    draft_groups     JSONB,
    created_at       TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_interview_record_hash ON interview_record(text_hash);

-- -----------------------------------------------------------------------------
-- 13. interview_knowledge_question —— 知识类面试问题
-- -----------------------------------------------------------------------------
CREATE TABLE interview_knowledge_question (
    id                    BIGSERIAL PRIMARY KEY,
    interview_record_id   BIGINT      NOT NULL REFERENCES interview_record(id) ON DELETE CASCADE,
    knowledge_node_id     BIGINT REFERENCES knowledge_node(id),
    tag                   VARCHAR(100) NOT NULL,
    questions             JSONB,
    user_answer           TEXT,
    original_dialogue     TEXT,
    score_result          JSONB,
    created_at            TIMESTAMP   NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_ikq_record ON interview_knowledge_question(interview_record_id);
CREATE INDEX idx_ikq_node   ON interview_knowledge_question(knowledge_node_id);

-- -----------------------------------------------------------------------------
-- 14. interview_project_question —— 项目类面试问题
-- -----------------------------------------------------------------------------
CREATE TABLE interview_project_question (
    id                    BIGSERIAL PRIMARY KEY,
    interview_record_id   BIGINT       NOT NULL REFERENCES interview_record(id) ON DELETE CASCADE,
    project_node_id       BIGINT REFERENCES project_node(id),
    project_name          VARCHAR(200) NOT NULL,
    questions             JSONB,
    user_answer           TEXT,
    original_dialogue     TEXT,
    score_result          JSONB,
    created_at            TIMESTAMP    NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_ipq_record ON interview_project_question(interview_record_id);
CREATE INDEX idx_ipq_node   ON interview_project_question(project_node_id);

-- -----------------------------------------------------------------------------
-- 15. interview_other_question —— 其他类面试问题（leetcode/hr/...）
-- -----------------------------------------------------------------------------
CREATE TABLE interview_other_question (
    id                  BIGSERIAL PRIMARY KEY,
    interview_record_id BIGINT      NOT NULL REFERENCES interview_record(id) ON DELETE CASCADE,
    content             TEXT        NOT NULL,
    tag                 VARCHAR(50) NOT NULL,
    user_answer         TEXT,
    extra               JSONB,
    created_at          TIMESTAMP   NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_ioq_record ON interview_other_question(interview_record_id);

-- -----------------------------------------------------------------------------
-- 16. user_answer_embedding —— 用户回答向量（Agent 长期记忆）
-- -----------------------------------------------------------------------------
CREATE TABLE user_answer_embedding (
    id                    BIGSERIAL PRIMARY KEY,
    user_id               BIGINT       NOT NULL DEFAULT 1,
    knowledge_point_id    BIGINT REFERENCES knowledge_node(id),
    source                VARCHAR(20)  NOT NULL,   -- 'interview' | 'study'
    knowledge_point_name  VARCHAR(200) NOT NULL,
    question_text         TEXT         NOT NULL,
    answer_text           TEXT         NOT NULL,
    embedding             VECTOR(1024),
    score                 SMALLINT,
    created_at            TIMESTAMP    NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_uae_user_kp ON user_answer_embedding(user_id, knowledge_point_id);
