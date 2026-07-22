-- =============================================================================
-- V67: 面试复盘「LeetCode 富化 agent」提示词种子（interview/leetcode-enrich）
--
-- 背景：算法题（type=algorithm）此前无题名/题号/链接。改为 agent：LLM 调 searchLeetCode
--       工具查真实题库、核对候选是否与面试口语描述一致，再产出规范题名+题号+slug。
-- 约定：prompt 全中文；输出结构化 JSON；不确定必须 matched=false（宁缺勿错，不编题）。
--       用户输入可能含语音转写错别字/口语噪声，按语义理解。
-- =============================================================================

INSERT INTO prompt_template (key, content, description) VALUES
('interview/leetcode-enrich', $PROMPT$你是算法题识别助手。下面是某次技术面试里出现的一道"手撕/算法"题的口语化描述（可能有错别字、语音转写噪声、表述不完整，请按语义理解）：

【面试中的描述】
{description}

你可以调用工具 searchLeetCode(keyword) 按【英文】关键词搜索 LeetCode 题库，它会返回候选题目的题号、题名、slug、难度。

请按以下步骤：
1. 从描述推断这道题最可能对应的 LeetCode 题目，用英文关键词调用 searchLeetCode（例如描述"手撕LRU"→关键词"LRU"；描述"两数之和"→"two sum"）。必要时可换关键词多试几次。
2. 核对候选题目是否与面试描述的**考察点/数据结构/操作**一致，选出最匹配的一个。
3. 判定：
   - 只有**高置信匹配**（确定就是这道题）才输出该题；
   - 描述模糊、无法确定、或明显是面试官自编/非标准 LeetCode 题 → matched=false，**绝不编造题号、题名或链接**。

只输出 JSON，不要 markdown、不要解释：
{"matched": true, "leetcode_id": "146", "title": "LRU Cache", "title_slug": "lru-cache"}

matched=false 时，其余字段填 null：
{"matched": false, "leetcode_id": null, "title": null, "title_slug": null}$PROMPT$,
'面试复盘：算法题 LeetCode 富化 agent（调 search 工具核对，输出题号/题名/slug；不确定 matched=false）')
ON CONFLICT (key) DO UPDATE SET content = EXCLUDED.content, description = EXCLUDED.description;
