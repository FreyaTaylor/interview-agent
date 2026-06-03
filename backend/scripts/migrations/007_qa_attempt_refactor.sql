-- 007: 答题/拷打模型重构 — "按题作答 + 整轮综合打分" 模型
--
-- 设计要点：
--   1. 一道"题"= 主问题 + N 个追问，最后才打一次综合分（更像真实面试）
--   2. 答题侧抽出独立 study_question 表（之前内嵌在 conversation.pending_questions JSONB）
--   3. 拷打侧问题就是 project_node 三级（level=3）叶子节点（已存在）
--   4. question_attempt 统一两侧：通过 question_type 区分
--   5. 分数聚合：题目分数=最近3次attempt平均；知识点mastery=所有题平均；项目准备度=所有话题平均
--   6. 旧表删除：study_session / conversation / conversation_message / mastery_record / mastery_history
--                project_session / project_session_message
--
-- 按用户决策：直接删除历史数据，不做数据迁移。

-- ===== 删除旧表（注意 FK 顺序）=====
DROP TABLE IF EXISTS mastery_history CASCADE;
DROP TABLE IF EXISTS mastery_record CASCADE;
DROP TABLE IF EXISTS conversation_message CASCADE;
DROP TABLE IF EXISTS conversation CASCADE;
DROP TABLE IF EXISTS study_session CASCADE;
DROP TABLE IF EXISTS project_session_message CASCADE;
DROP TABLE IF EXISTS project_session CASCADE;

-- ===== 删除历史遗留列 =====
ALTER TABLE interview_record DROP COLUMN IF EXISTS study_session_id;

-- ===== 新表 1：study_question — 知识点下的预生成题目 =====
CREATE TABLE study_question (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT DEFAULT 1,
    knowledge_point_id BIGINT NOT NULL REFERENCES knowledge_node(id) ON DELETE CASCADE,
    content TEXT NOT NULL,
    -- Rubric 模板：[{"key_point": "...", "weight": 20, "description": "..."}]
    rubric_template JSONB NOT NULL DEFAULT '[]'::jsonb,
    -- 预生成的范例回答（用户口吻）：JSONB 列表或字符串
    recommended_answer JSONB,
    sort_order INTEGER DEFAULT 0,
    created_at TIMESTAMP DEFAULT NOW()
);
CREATE INDEX idx_study_question_kp ON study_question(knowledge_point_id, sort_order);

-- ===== 新表 2：question_attempt — 一次完整作答（主问+所有追问，最后综合打分）=====
-- 统一两侧：通过 question_type + question_id 多态关联
--   question_type='study'   -> question_id 指 study_question.id
--   question_type='project' -> question_id 指 project_node.id (level=3 叶子)
CREATE TABLE question_attempt (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT DEFAULT 1,
    question_type VARCHAR(20) NOT NULL CHECK (question_type IN ('study', 'project')),
    question_id BIGINT NOT NULL,
    -- 状态：in_progress / finished / abandoned
    status VARCHAR(20) NOT NULL DEFAULT 'in_progress',
    -- 综合评分（仅 finished 时有值，0-100）
    final_score SMALLINT,
    -- Rubric 命中明细：[{"key_point": "...", "hit": true, "score": 18, "matched_text": "..."}]
    rubric_result JSONB,
    -- 整体反馈/总结
    overall_summary TEXT,
    -- 设计问题（仅拷打侧使用）
    design_issues JSONB,
    -- 完整对话流：[{role:'agent'|'user', type:'question'|'answer'|'follow_up'|'feedback',
    --             content, recommended_answer?, covered?, asked_at}]
    dialog JSONB NOT NULL DEFAULT '[]'::jsonb,
    -- 已追问轮数（不含主问）
    follow_up_count SMALLINT DEFAULT 0,
    finished_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT NOW()
);
-- 查询热点：取某题最近 N 次 finished 的 final_score
CREATE INDEX idx_attempt_question_finished
    ON question_attempt(question_type, question_id, status, finished_at DESC);
-- 用户维度查询
CREATE INDEX idx_attempt_user ON question_attempt(user_id, created_at DESC);
