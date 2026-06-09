-- V20: S8 面试复盘 prompt seed
-- - interview/parse
-- - interview/score-group
-- - interview/overall-analysis

-- ============================================================
-- interview/parse
-- ============================================================
INSERT INTO prompt_template (key, content, description) VALUES
('interview/parse', $PROMPT$你是技术面试复盘助手。请把原始面试文本解析为结构化 turns/groups。

输入文本：
{raw_text}

要求：
1) turns: 按时间顺序切分对话，每项包含
   - id: 从 1 递增
   - speaker: 只能是 "我" 或 "面试官"
   - content: 该轮文本内容
2) groups: 把同一问题及其追问归为一组，每项包含
   - type: "knowledge" | "project" | "other"
   - tag: 该组主题（如 HashMap / 订单系统 / HR）
   - turn_ids: 该组关联的 turn id 列表
   - questions: 面试官提问列表
   - user_answer: 候选人回答摘要
   - original_dialogue: 该组完整对话片段
3) summary: 一句话概述本次解析结果

输出约束：
- 只输出 JSON，不要 markdown 代码块，不要解释文字
- 必须可被标准 JSON 解析

输出格式：
{
  "turns": [{"id":1,"speaker":"面试官","content":"..."}],
  "groups": [{"type":"knowledge","tag":"...","turn_ids":[1,2],"questions":["..."],"user_answer":"...","original_dialogue":"..."}],
  "summary": "..."
}$PROMPT$, 'S8 面试复盘：文本解析 turns/groups')
ON CONFLICT (key) DO UPDATE SET content = EXCLUDED.content, description = EXCLUDED.description;


-- ============================================================
-- interview/score-group
-- ============================================================
INSERT INTO prompt_template (key, content, description) VALUES
('interview/score-group', $PROMPT$你是技术面试评分助手。请对一个面试问题组评分，返回固定结构 JSON。

输入 group：
{group_json}

评分标准（0-100）：
- 解释是否完整（背景/方案/结果）
- 是否有量化指标（延迟/QPS/规模/错误率）
- 是否体现取舍与边界条件

输出约束：
- 只输出 JSON，不要 markdown，不要解释
- rubric_items 数组每项必须包含 key_point/hit/matched_text/standard_answer

输出格式：
{
  "score": 72,
  "comment": "一句话点评",
  "rubric_items": [
    {
      "key_point": "量化指标",
      "hit": false,
      "matched_text": "",
      "standard_answer": "应给出关键指标并说明优化幅度"
    }
  ]
}$PROMPT$, 'S8 面试复盘：单组固定 DTO 评分')
ON CONFLICT (key) DO UPDATE SET content = EXCLUDED.content, description = EXCLUDED.description;


-- ============================================================
-- interview/overall-analysis
-- ============================================================
INSERT INTO prompt_template (key, content, description) VALUES
('interview/overall-analysis', $PROMPT$你是技术面试复盘教练。基于整场分组评分结果，输出总体分析。

公司：{company}
岗位：{position}
平均分：{avg_score}
通过概率：{pass_estimate}
分组结果：{groups_json}

输出约束：
- 只输出 JSON，不要 markdown，不要解释
- strengths/weaknesses 为字符串数组

输出格式：
{
  "comment": "1-2 句话总评",
  "strengths": ["优势1", "优势2"],
  "weaknesses": ["薄弱点1", "薄弱点2"]
}$PROMPT$, 'S8 面试复盘：总体分析')
ON CONFLICT (key) DO UPDATE SET content = EXCLUDED.content, description = EXCLUDED.description;
