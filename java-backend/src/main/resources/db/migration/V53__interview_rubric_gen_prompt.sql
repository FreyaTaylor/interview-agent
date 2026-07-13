-- V53: interview/rubric-gen —— 面试真题 rubric（追问链 = 采分点）
-- 背景：设计 2026-07-12。面试真题落库即 eager 生成 rubric；输入是"主问 + 追问链 + 我当时的回答"。
--        核心洞察：面试官之所以追问，是主问没答全——所以【追问 = 主问 rubric 的采分点】。
-- 与 study/rubric-gen 区别：那个只吃题干；这个额外吃追问链 + user_answer，让采分点精确还原"面试官到底考了什么"。
-- 输出结构与 study/rubric-gen 对齐（rubric[{key_point,hit_rule,score}] + recommended_answer[]），便于答题命中表复用。

INSERT INTO prompt_template (key, content, description) VALUES
('interview/rubric-gen', $PROMPT$你是一位资深技术面试官。下面是一场真实面试里，围绕**一个主问**的追问链和候选人的回答。请把它提炼成该主问的评分 Rubric 与范例答案，**只返回 JSON**。

## 主问（规范化题干）
{question}

## 面试官的追问链（按顺序）
{follow_ups}

## 候选人当时的回答
{user_answer}

## 所属分类路径（领域约束）
{category_path}

## ⚠️ 核心逻辑
面试官**逐层追问，是因为主问没答全**——所以**每一条追问都对应一个采分点**（候选人本该在主问里覆盖、却漏掉的点）。你的任务：把"主问该覆盖什么"提炼成采分点，**追问问到的点必须成为采分点**。

## 要求
1. 给出 **3-5 个 Rubric 评分点**，score 之和 = 100。
2. **只覆盖主问字面范围 + 追问延伸的范围**，不扩展到无关维度。
3. `key_point`：采分点名，≤8 字。
4. `hit_rule`：命中规则，≤24 字，说明"答到什么算命中"（如"提到可达性分析或 GC Roots 即命中"）。
5. `recommended_answer`：**3-5 条要点的字符串数组**，第一人称、每条 30-80 字，覆盖全部采分点。
   **每条用 Markdown 强调**：关键术语 **加粗**，类名/方法/参数用 `反引号`。

## 严格按下面 JSON 输出（不要围栏、不要解释）
```json
{
  "rubric": [
    {"key_point": "关键点（≤8字）", "hit_rule": "命中规则（≤24字）", "score": 25}
  ],
  "recommended_answer": ["**要点**：含 `code` 的第一人称陈述。"]
}
```
$PROMPT$, 'Interview 模块：面试真题 rubric（追问链=采分点 + Markdown 范例答案）')
ON CONFLICT (key) DO UPDATE SET content = EXCLUDED.content, description = EXCLUDED.description;
