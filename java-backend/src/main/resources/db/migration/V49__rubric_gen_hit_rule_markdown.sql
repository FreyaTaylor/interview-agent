-- V49: study/rubric-gen 增"命中规则" + 参考答案用 Markdown 强调
-- 背景：学习页参考答案框展示 rubric，用户要"采分点 + 命中规则 + 权重"三列（参考设计 demo 的形态B），
--        且参考答案要点应有 Markdown 强调（加粗术语、反引号包 API），否则渲染出来一片平文本。
-- 现象：旧 rubric 只有 {key_point, score}，缺命中规则；recommended_answer 是纯文本无 Markdown。
-- 根因：V39 prompt 未产 hit_rule、未要求 recommended_answer 带 Markdown。
-- 修复：rubric 每点加 hit_rule(命中规则)；recommended_answer 要求关键术语加粗、类名/方法/参数用反引号。

INSERT INTO prompt_template (key, content, description) VALUES
('study/rubric-gen', $PROMPT$你是一位资深技术面试官。下面给你**一道已确定的面试题**，请为它生成评分 Rubric 与范例答案，**只返回 JSON**。

## 面试题
{question}

## 所属分类路径（领域约束）
{category_path}

## ⚠️ 领域约束
**必须严格按「所属分类路径」确定领域！**（redis→Redis、mysql→MySQL、Java→Java）即使概念在别的技术里同名，也按当前路径对应技术理解题目。

## 要求
1. 给出 **3-5 个 Rubric 评分点**，所有 score 之和 = 100。
2. **题干-评分一致性（必须严格）**：rubric 只能覆盖该题**题干明确问到的范围**，不得扩展到同主题其他维度。
   - 例：题干问"如何触发"，rubric 只能是触发相关（配置、命令、自动条件），不能出现"文件结构/写入流程"。
3. `key_point`：采分点名，≤8 字，精准概括该评分点。
4. `hit_rule`：**命中规则**，≤24 字，说明"答到什么才算命中该点"，用于人工/自动判分。例："提到可达性分析或 GC Roots 即命中"。
5. `recommended_answer`：**3-5 条要点的字符串数组**，第一人称直接陈述，每条 30-80 字，含关键概念/原理/数据/对比。
   **每条用 Markdown 强调**：关键术语与结论 **加粗**；类名/方法/参数/关键字/命令用 `反引号` 包裹（如 `SoftReference`、`get()`、`-XX:SoftRefLRUPolicyMSPerMB`、`WeakHashMap`）。

## 严格按下面 JSON 输出（不要围栏、不要解释）
```json
{
  "rubric": [
    {"key_point": "关键点（≤8字）", "hit_rule": "命中规则（≤24字）", "score": 25}
  ],
  "recommended_answer": ["**强引用**：只要引用链可达就**绝不回收**，即使 `OutOfMemoryError` 也不回收。"]
}
```
$PROMPT$, 'Study 模块：给定题干懒生成 Rubric（采分点+命中规则+权重）+ Markdown 范例答案')
ON CONFLICT (key) DO UPDATE SET content = EXCLUDED.content, description = EXCLUDED.description;
