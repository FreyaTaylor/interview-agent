-- =============================================================================
-- V74: 「看看面经」单问题 rubric + 推荐答案 提示词种子（interview-exp/rubric-gen）
--
-- 背景：看看面经点开一个问题，懒生成采分点 + 分点范例答案（形状对齐 study/rubric-gen，
--       供前端参考答案框复用同一渲染）。领域约束用「知识域」（domain）替代知识树分类路径。
-- 输出：{rubric:[{key_point,hit_rule,score}], recommended_answer:["...markdown..."]}
-- =============================================================================

INSERT INTO prompt_template (key, content, description) VALUES
('interview-exp/rubric-gen', $PROMPT$你是一位资深技术面试官。下面给你**一道真实面经里的面试题**，请为它生成评分 Rubric 与范例答案，**只返回 JSON**。

## 面试题
{question}

## 所属知识域（领域约束）
{domain}

## ⚠️ 领域约束
**必须严格按「所属知识域」确定领域！**（Redis→Redis、MySQL→MySQL、Java→Java）即使概念在别的技术里同名，也按当前知识域对应技术理解题目。

## 要求
1. 给出 **3-5 个 Rubric 评分点**，所有 score 之和 = 100。
2. **题干-评分一致性（必须严格）**：rubric 只能覆盖该题**题干明确问到的范围**，不得扩展到同主题其他维度。
3. `key_point`：采分点名，≤8 字，精准概括该评分点。
4. `hit_rule`：**命中规则**，≤24 字，说明"答到什么才算命中该点"。例："提到可达性分析或 GC Roots 即命中"。
5. `recommended_answer`：**3-5 条要点的字符串数组**，第一人称直接陈述，每条 30-80 字，含关键概念/原理/数据/对比。
   **每条用 Markdown 强调**：关键术语与结论 **加粗**；类名/方法/参数/关键字/命令用 `反引号` 包裹。

## 严格按下面 JSON 输出（不要围栏、不要解释）
```json
{
  "rubric": [
    {"key_point": "关键点（≤8字）", "hit_rule": "命中规则（≤24字）", "score": 25}
  ],
  "recommended_answer": ["**强引用**：只要引用链可达就**绝不回收**，即使 `OutOfMemoryError` 也不回收。"]
}
```
$PROMPT$, '看看面经：给定面经题懒生成 Rubric（采分点+命中规则+权重）+ Markdown 范例答案')
ON CONFLICT (key) DO UPDATE SET content = EXCLUDED.content, description = EXCLUDED.description;
