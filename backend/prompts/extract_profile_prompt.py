"""
项目画像抽取 Prompt — 答题后异步调用，从 (现有画像 + 本轮 Q&A) 生成 patch

输出契约（必须是严格 JSON）：
{
  "facts_patch": {
    "add":    ["新增的项目事实描述（完整一段话）", ...],
    "update": [
      {"old": "<现有档案里的原文，必须与现状完全一致>",
       "new": "<改写或扩充后的完整描述>"}
    ],
    "remove": ["<已被证伪/作废的旧条目原文>", ...]
  },
  "weak_points_add": [
    {"topic": "...", "point": "...", "question": "...", "round": 3}
  ],
  "weak_points_resolved": ["要删除的旧 point 文本", ...]
}

要点：
- project_facts 是「候选人对自己项目的事实性描述」的扁平列表，**不分 section**
- 每条 fact 是一段较完整的描述（可单句也可短段），随着多轮答题逐步扩充
- 优先 update（在已有条目上改写/扩充），其次 add（确实是新维度时）
- update.old 必须与现存条目完全一致，否则会被忽略
- weak_points.point 描述要短（≤20字），question 是当时问的原题
"""

EXTRACT_PROFILE_PROMPT = """你是一名技术面试官的助手，负责维护「候选人项目档案」。
基于本轮答题，从中抽取信息并输出 patch 来更新档案。

## 候选人项目
- 项目名：{project_name}
- 项目描述：{project_description}

## 当前档案（你需要在此基础上做增量更新）
### 项目事实档案（扁平列表，每条是一段完整描述）
{current_facts}

### 已记录的薄弱点
{current_weak_points}

## 本轮 Q&A
- 维度：{topic}
- 问题：{question}
- 候选人回答：{answer}
- 评分总结：{scoring_summary}
- 评分未命中要点：{missed_key_points}

## 你的任务

### 1. project_facts patch（项目事实档案更新）

档案是一个**扁平的事实描述列表**，每条 fact 描述项目的某一方面：
业务规模、技术架构、模块设计、关键流程、决策权衡、踩坑、改造收益…… 都可以。

**核心原则**：
- 每条 fact 是一段**自洽的、较完整的描述**，可以是一句也可以是几句拼成的小段；
  例：「订单状态机由 CREATED/PAID/SHIPPED 三态组成，仅允许 CREATED→PAID→SHIPPED 单向流转，
        由 ShardingSphere 路由到对应分库后用乐观锁更新」
- **优先 update**：如果本轮回答是对已有 fact 的补充/扩展/修正，请**改写整条 fact**，
  把新信息融入原描述，而不是新增一条相似条目。
  例：原 fact = "订单状态机由三态组成，流转方式为 a→b→c"
      本轮答出"为什么这样设计"
      → update: {{"old": "订单状态机由三态组成，流转方式为 a→b→c",
                  "new": "订单状态机由三态组成，流转方式为 a→b→c；这样设计是为了..."}}
- 只有当本轮揭示了**全新维度**（已有条目都涵盖不了）时才用 add
- 用 remove 删除被本轮回答**明确推翻**的旧 fact（少见）
- 单条 fact 长度建议 30~200 字，言简意赅

### 2. weak_points patch（薄弱点更新）
- weak_points_add: 本轮新暴露的薄弱点（每条 topic+point+question+round）
- weak_points_resolved: 本轮回答**已经覆盖**的历史薄弱点（直接传原 point 文本）

## 输出格式（严格 JSON，无任何解释文字）
```json
{{
  "facts_patch": {{
    "add":    [],
    "update": [],
    "remove": []
  }},
  "weak_points_add": [],
  "weak_points_resolved": []
}}
```

只输出 JSON 代码块，不要前后任何说明。
"""
