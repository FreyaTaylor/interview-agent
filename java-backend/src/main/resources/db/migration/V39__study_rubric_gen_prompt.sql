-- V39: Rubric 懒生成 prompt（配合"讲解按面试题驱动"重构）
-- 背景：Step A 只产题干（study_question.content），rubric_template + recommended_answer 改为
--   "用户首次答该题时"懒生成（study 端 ensureRubric 钩子调用）。故需一个"给定单个题干 → 产 rubric"的 prompt。
-- 与 learn/question-gen 的区别：那个是"一次出 N 道题(含题干+rubric)"，这里是"给定题干补 rubric"。

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
3. `key_point` ≤8 字，精准概括该评分点。
4. `recommended_answer`：**3-5 条要点的字符串数组**，第一人称直接陈述，每条 30-80 字，含关键概念/原理/数据/对比。

## 严格按下面 JSON 输出（不要围栏、不要解释）
```json
{
  "rubric": [
    {"key_point": "关键点（≤8字）", "score": 25}
  ],
  "recommended_answer": ["要点1", "要点2", "要点3"]
}
```
$PROMPT$, 'Study 模块：给定题干懒生成 Rubric + 范例答案')
ON CONFLICT (key) DO UPDATE SET content = EXCLUDED.content, description = EXCLUDED.description;
