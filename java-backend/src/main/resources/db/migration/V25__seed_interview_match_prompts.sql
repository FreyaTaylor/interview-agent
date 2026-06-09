-- =============================================================================
-- V25: 面试节点匹配 prompts —— 忠实复刻 Python 内联 prompt
--   1) interview/match-knowledge-rerank ← embedding_match_skill.py::RERANK_PROMPT
--   2) interview/match-project-root      ← project_node_matcher.py::match_or_create_project_root 内联
--   3) interview/match-project-topic     ← project_node_matcher.py::match_or_create_topic 内联
-- 占位符与 Python .format 完全一致；Python 的 {{ }} 已还原为单花括号。
-- =============================================================================

INSERT INTO prompt_template (key, content, description) VALUES
('interview/match-knowledge-rerank', $PMT$你正在为面试问题匹配最合适的知识点。

## 面试问题
{text}

## 候选知识点（已按向量相似度排序）
{candidates}

## 规则
- 仔细判断面试问题真正考察的核心知识点
- 用户输入可能有口误/语音转写错误，按语义而非字面匹配
- 如果有明显匹配的候选，输出该候选的 id
- 如果所有候选都和问题考察点不符（哪怕距离很近），输出 null
- 例如：问"Redis 持久化" 但候选只有"Redis 数据结构"——输出 null

## 输出格式（JSON）
```json
{"node_id": 123, "reason": "简要理由"}
```
或
```json
{"node_id": null, "reason": "都不匹配"}
```

只返回 JSON。$PMT$, $PMT$S8 面试复盘：知识点 embedding 召回后的 LLM rerank（复刻 embedding_match_skill RERANK_PROMPT，占位符 {text}{candidates}）$PMT$)
ON CONFLICT (key) DO UPDATE SET content = EXCLUDED.content, description = EXCLUDED.description;

INSERT INTO prompt_template (key, content, description) VALUES
('interview/match-project-root', $PMT$候选人真实项目列表：
{catalog}

面试中 AI 提取的项目名：「{name}」

判断这个名字是否对应列表中的某个项目（语义匹配，允许名字不完全相同）。
- 对应某个项目 → 返回该项目的 id
- 都不对应（AI 编造、描述模糊、明显不同的项目）→ 返回 null

只输出 JSON：
```json
{"id": 123}
```
$PMT$, $PMT$S8 面试复盘：项目根 LLM 语义匹配（复刻 project_node_matcher.match_or_create_project_root 内联，占位符 {catalog}{name}）$PMT$)
ON CONFLICT (key) DO UPDATE SET content = EXCLUDED.content, description = EXCLUDED.description;

INSERT INTO prompt_template (key, content, description) VALUES
('interview/match-project-topic', $PMT$现有话题分类：
{catalog}

面试中 AI 提取的话题：「{topic}」

判断是否对应某个现有话题（语义匹配，允许名字不完全相同）。
- 对应某个话题 → 返回该话题的 id
- 都不对应 → 返回 null

只输出 JSON：
```json
{"id": 12}
```
$PMT$, $PMT$S8 面试复盘：项目话题 LLM 语义匹配（复刻 project_node_matcher.match_or_create_topic 内联，占位符 {catalog}{topic}）$PMT$)
ON CONFLICT (key) DO UPDATE SET content = EXCLUDED.content, description = EXCLUDED.description;
