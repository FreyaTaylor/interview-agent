-- V34: 自感知掌握度（用户在学习页手动设置，与答题派生掌握度相互独立）
-- 背景：有时用户只看知识点讲解、不做题，也认为自己掌握了。
--   答题派生的 mastery_level 不会反映这种情况，故新增一个用户自评字段。
-- 约定：
--   - self_mastery 与 mastery_level 各自独立存储、独立展示；
--   - 取值 0-100（前端三档：了解=40 / 掌握=75 / 熟练=100），null = 未自评；
--   - 知识树圆环按"有效掌握度 = max(mastery_level, self_mastery)"渲染。
ALTER TABLE knowledge_node
    ADD COLUMN IF NOT EXISTS self_mastery SMALLINT;

COMMENT ON COLUMN knowledge_node.self_mastery IS
    '用户自评掌握度 0-100（了解40/掌握75/熟练100），null=未自评；与 mastery_level（答题派生）相互独立';
