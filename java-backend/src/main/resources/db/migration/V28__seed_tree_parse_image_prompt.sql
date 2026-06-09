-- V28: 知识树「截图解析」视觉 prompt 种子（from-image / qwen-vl-max）。
-- 复刻 Python backend/prompts/tree_prompts.py 的 TREE_PARSE_IMAGE_PROMPT。
-- 注意：本模板无占位符，取用走 PromptService.load()（不渲染），故 JSON 示例里的
--       花括号写「单括号」即可，无需像 Python .format 那样转义成 {{ }}。
-- ON CONFLICT (key) DO NOTHING：已存在不覆盖，保留运营编辑。

INSERT INTO prompt_template (key, content, description) VALUES
('tree/parse-image', $PROMPT$你是一个纯格式解析器。请将这张截图中的内容按层级关系解析为树状 JSON 结构。

## ❗❗❗ 核心规则
1. **严格保持原文**：节点名称必须和图片中的文字完全一致，不得改写、缩写、归纳、翻译或提炼关键词
2. **不增不删**：图片中有什么就输出什么，不要添加、合并、拆分或省略任何节点
3. **只做层级解析**：根据图片中的缩进、连线、树形结构等判断父子关系
4. **禁止自动归类**：如果图片中是扁平列表（没有层级关系），就直接输出为扁平 children，不要按含义自行归组或创建父分类
5. 为整棵树起一个简短名称（≤15字）
6. 叶子节点的 interview_weight 统一设为 3

## 输出格式
```json
{
  "name": "知识树名称",
  "children": [
    {
      "name": "图片中的原文（保持原文）",
      "children": [
        {"name": "图片中的原文（保持原文）", "interview_weight": 3}
      ]
    }
  ]
}
```

只返回 JSON，不要其他内容。$PROMPT$, 'Admin 树生成：截图视觉解析为知识树（qwen-vl-max）')

ON CONFLICT (key) DO NOTHING;
