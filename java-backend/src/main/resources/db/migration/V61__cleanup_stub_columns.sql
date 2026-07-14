-- =============================================================================
-- V61: 清理三模块废弃列 + 退役 prompt（module-review 梳理结论）
--
-- 背景：module-review 复盘（local/java-backend-docs/reviews/2026-07-14-*）确认以下为 write-only / 退役：
--   1) interview_knowledge_question.knowledge_node_id —— 早期 1:1「真题↔知识点」stub，
--      三模块解耦后由 interview_question_kp_link（N:N）接管；仅 INSERT 写、全库无读取 → 删列。
--   2) tree_node.is_user_created —— 仅知识树 INSERT 写 false、全库无读取/过滤 → 删列（project 靠 DEFAULT，不受影响）。
--   3) prompt_template 'learn/question-gen' —— 出题路径退役（题目由 Step A 讲解生成产出），代码已删 → 删种子行。
-- 不兼容历史：开发期不做兼容，直接删（已人工确认）。
-- =============================================================================

DROP INDEX IF EXISTS idx_ikq_node;
ALTER TABLE interview_knowledge_question DROP COLUMN IF EXISTS knowledge_node_id;

ALTER TABLE tree_node DROP COLUMN IF EXISTS is_user_created;

DELETE FROM prompt_template WHERE key = 'learn/question-gen';
