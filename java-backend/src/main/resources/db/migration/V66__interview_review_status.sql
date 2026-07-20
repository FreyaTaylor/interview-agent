-- =============================================================================
-- V66: interview_record 增加「复盘状态」列
--
-- 背景：面试复盘需要区分"待复盘 / 已复盘"，编辑弹框里可切换、列表页展示徽章。
-- 现象：interview_record 无复盘状态字段，无法标记一条面试是否已复盘。
-- 修复：加 review_status VARCHAR(16) NOT NULL DEFAULT 'pending'
--       取值 pending=待复盘（默认）/ reviewed=已复盘。历史行由 DEFAULT 落到 pending。
-- =============================================================================

ALTER TABLE interview_record
    ADD COLUMN IF NOT EXISTS review_status VARCHAR(16) NOT NULL DEFAULT 'pending';
