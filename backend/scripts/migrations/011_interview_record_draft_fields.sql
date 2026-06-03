-- 011: 为 interview_record 增加草稿字段
-- 用于"保存校准"功能：用户在校对页可以保存当前编辑而不触发解析/评分
-- finalize / recalibrate 成功后清空 draft_*

ALTER TABLE interview_record
    ADD COLUMN IF NOT EXISTS draft_turns JSONB,
    ADD COLUMN IF NOT EXISTS draft_groups JSONB;
