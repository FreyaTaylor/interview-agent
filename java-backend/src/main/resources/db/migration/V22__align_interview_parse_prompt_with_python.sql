-- V22: 对齐 interview/parse 提示词到 Python 主流程的分类与分组约束
-- 目标：减少“未分类”与错分组，确保输出字段与 Java/Python 双端一致

INSERT INTO prompt_template (key, content, description) VALUES
('interview/parse', $PROMPT$你是一位资深技术面试分析专家。请把面试文本解析为结构化 JSON。

输入文本：
{raw_text}

任务要求：
1) 先切分 turns：
   - id: 从 1 开始递增
   - speaker: 只能是"面试官"或"我"
   - content: 对应轮次内容
2) 再聚合 groups：同一问题及其追问必须归为同一组
3) 识别分组三类 category：knowledge / project / other
4) 同时补充 legacy 字段 type，映射规则：
   - knowledge -> knowledge
   - project -> project
   - other + tag=leetcode -> algorithm
   - other + tag=hr -> hr
   - 其余 other -> other

分组字段要求：
- category: knowledge | project | other
- type: knowledge | project | algorithm | hr | other
- tag: 主题短标签，不允许空；若无法判断填"misc"
- project_name: 仅 project 类必填，其他可省略
- turn_ids: 该组覆盖的 turn id（升序，去重）
- questions: 该组内面试官问题数组（书面化、去口语噪声）
- user_answer: 候选人回答摘要（客观技术描述）
- original_dialogue: 原始对话片段（保留“面试官：...\n我：...”）

关键判定：
- project：围绕候选人项目深挖（如“你们项目/你们系统怎么做”）
- knowledge：通用技术原理（不依赖候选人具体项目）
- other：leetcode / hr / system_design / misc

输出约束：
- 只输出 JSON，不要 markdown，不要解释
- 必须可被标准 JSON 解析
- 不要漏字段，不要输出空对象

输出格式：
{
  "turns": [
    {"id": 1, "speaker": "面试官", "content": "..."}
  ],
  "groups": [
    {
      "category": "knowledge",
      "type": "knowledge",
      "tag": "MySQL 事务隔离级别",
      "turn_ids": [1, 2, 3],
      "questions": ["MySQL 的事务隔离级别有哪些？"],
      "user_answer": "...",
      "original_dialogue": "面试官：...\n我：..."
    },
    {
      "category": "project",
      "type": "project",
      "project_name": "订单系统",
      "tag": "缓存一致性",
      "turn_ids": [4, 5, 6],
      "questions": ["你们项目里缓存一致性怎么做？"],
      "user_answer": "...",
      "original_dialogue": "面试官：...\n我：..."
    },
    {
      "category": "other",
      "type": "algorithm",
      "tag": "leetcode",
      "turn_ids": [7],
      "questions": ["手撕反转链表"],
      "user_answer": "...",
      "original_dialogue": "面试官：..."
    }
  ],
  "summary": "..."
}$PROMPT$, 'S8 面试复盘：对齐 Python 解析规则（category/type/tag/turn_ids）')
ON CONFLICT (key) DO UPDATE SET content = EXCLUDED.content, description = EXCLUDED.description;
