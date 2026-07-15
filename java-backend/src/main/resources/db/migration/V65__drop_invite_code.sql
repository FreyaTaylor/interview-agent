-- 背景：邀请码功能「一期预留、二期未做」，线上 IAGENT_INVITE_REQUIRED 恒 false，从未真正启用。
-- 修复：随「删除邀请码逻辑」（Spec 2026-07-15）一并移除 invite_code 表。
-- 表已清空（0 行），DROP 无数据损失；其对 user 的两个 FK 随表一并移除。
DROP TABLE IF EXISTS invite_code;
