-- =============================================================================
-- V73: 「看看面经」内容侧表 interview_exp_question_detail
--
-- 背景：面经问题(question 节点)要像知识点子话题那样懒生成「讲解 + rubric + 推荐答案」。
-- 设计：local/java-backend-docs/design/2026-07-24-interview-exp-study-page.md
-- 复用：结构 = subtopic_detail(body_md/content_status) + question_detail(rubric/answer) 合体；
--       与 tree_node 1:1（PK=FK，级联删）。自评掌握度复用 tree_node.self_mastery，无需新列。
--       "先落新库、后融合"：独立表，不污染 study 的 question_detail 语义。
-- =============================================================================

CREATE TABLE interview_exp_question_detail (
    node_id            BIGINT PRIMARY KEY REFERENCES tree_node(id) ON DELETE CASCADE,
    body_md            TEXT,                                    -- 懒生成讲解正文，可空
    content_status     VARCHAR(16) NOT NULL DEFAULT 'pending',  -- pending | ready
    rubric_template    JSONB,                                   -- [{key_point, hit_rule, score}]
    recommended_answer JSONB,                                   -- 分点范例答案
    created_at         TIMESTAMP   NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMP   NOT NULL DEFAULT NOW()
);
