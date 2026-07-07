-- V41: Step B 单子话题深度正文 prompt（目标题驱动 · 懒生成）
-- 背景：Step A 只产【子话题+目标题】，点击某子话题时才用本 prompt 生成"能自如答出目标题"的深度正文。
--   聚焦单个子话题 + 它的目标题 → 模型预算全花一处 → 深；要求讲到机制层(为什么)，而非罗列结论。

INSERT INTO prompt_template (key, content, description) VALUES
('learn/subtopic-content', $PROMPT$你是一位资深技术面试辅导专家。请为下面这个子话题写一段**深度讲解正文**，目标是：读完能**自如回答**列出的面试题。**只返回 Markdown 正文**（不要 JSON、不要标题行、不要围栏）。

## 子话题
{title}

## 所属分类路径
{category_path}

## 学完应能回答的面试题（目标题）
{target_questions}

## ❗ 深度要求（核心）
不要罗列结论、不要"是什么"清单。必须讲到**机制层**，让读者能**推理**而非**背诵**：
- 讲**为什么**：底层机制、因果链（A 导致 B 导致 C），而不是只给结论。
- 每个关键结论都要能追溯到机制。例：不要只说"synchronized 会钉住虚拟线程"，要讲清"synchronized 依赖对象头 monitor + 绑定平台线程栈 → 虚拟线程无法 unmount → 钉住"。
- **精炼**：宁可少讲两点、每点讲透到机制，也不要面面俱到地浅。

## 输出格式
- 纯 Markdown 正文，2-5 段；关键词 **加粗**；可含 ≤12 行代码块或一个对比表。
- 每段聚焦一点，每句 ≤30 汉字，能跳读。
- **不要**在正文里嵌"面试常问/面试追问"问题行（目标题已单独展示）。
- **不要**写"总结/小结"段。

## 自检（输出前必做）
逐条看目标题：读完这段正文，能不能**答出每一道**？答不出的点 → 补上其机制。

只返回 Markdown 正文。$PROMPT$, 'Learn 模块：单子话题深度正文（Step B 懒生成，讲到机制层能答出目标题）')
ON CONFLICT (key) DO UPDATE SET content = EXCLUDED.content, description = EXCLUDED.description;
