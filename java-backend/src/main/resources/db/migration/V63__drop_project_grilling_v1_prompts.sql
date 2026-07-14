-- V63：删除项目拷问 v1 旧 prompt（已被 v2 独占，回退不再需要）
--
-- 背景：V16 种了 project/per-turn、project/final-score（v1）；V17 引入 v2 版
--       （project/per-turn-v2、project/final-score-v2）并注明「旧 prompt 保留作回退」。
-- 现象：v1 prompt 仅被 ProjectQaStrategy 引用，而该类已随本次 module-review 删除，
--       生产链路只走 v2。
-- 根因：回退窗口早已过，v1 成为死数据 + 死 key。
-- 修复：删两行 prompt_template（同步已删 ProjectQaStrategy 与 PromptKeys 的 v1 常量）。
DELETE FROM prompt_template WHERE key IN ('project/per-turn', 'project/final-score');
