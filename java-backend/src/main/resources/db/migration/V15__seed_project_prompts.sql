-- V15: 项目树 Admin (S6) prompt seed
-- - project/parse-text   把项目描述拆为 项目 → 话题 → 问题 三层 JSON 树
-- - project/dup-check    判定新项目名是否与已有项目语义重复
-- 用 ON CONFLICT (key) DO UPDATE 强制覆盖，便于后续迭代

INSERT INTO prompt_template (key, content, description) VALUES
('project/parse-text', $PROMPT$你是一位资深技术面试官。用户会输入一段项目描述（可能来自简历、语音转文字、或口头介绍），请将其拆解为一棵"项目拷打树"。

## 用户输入
{text}

## 拆解规则

### 层级结构（严格3层）
1. **项目名**（根节点）：从描述中提取出简短的项目名称（≤15字）
2. **话题**（二级节点）：面试官会从哪几个角度拷打这个项目（3-8个话题）
3. **问题**（三级叶子）：每个话题下的具体面试问题（每个话题 2-6 个问题）

### 话题拆解要求
- 话题名简短（≤10字），是面试官切入的角度
- 常见角度：技术选型、架构设计、难点与解决、性能优化、数据方案、上线踩坑、收获反思 等
- 根据项目实际内容选择合适的角度，不是每个角度都需要
- 不要生成和项目无关的泛泛话题

### 问题生成要求
- 问题是面试官真正会问的具体问题（口语化、直接）
- ❌ "请介绍一下项目背景" — 太泛，不是拷打
- ✅ "为什么选 Kafka 而不是 RocketMQ？" — 具体、有针对性
- ✅ "高峰期 QPS 多少？怎么扛住的？" — 真实面试会追问的
- 问题应该能让候选人展示深度，而非简单描述

## 输出格式
```json
{
  "name": "项目名称",
  "children": [
    {
      "name": "话题名称",
      "children": [
        {"name": "具体面试问题"},
        {"name": "具体面试问题"}
      ]
    }
  ]
}
```

只返回 JSON，不要其他内容。$PROMPT$, 'S6 项目树：把项目描述拆为 项目→话题→问题 三层树')

ON CONFLICT (key) DO UPDATE SET content = EXCLUDED.content, description = EXCLUDED.description;


INSERT INTO prompt_template (key, content, description) VALUES
('project/dup-check', $PROMPT$判断新项目名称是否与已有项目列表中的某一个语义相同或高度相似（指的是同一个项目）。

## 新项目名称
{new_name}

## 已有项目列表
{existing_names}

## 规则
- 语义相同：如 "商品推荐系统" 和 "推荐系统" 和 "电商推荐平台" 是同一项目
- 语义相同：如 "订单中台" 和 "交易中台-订单模块" 是同一项目
- 语义不同：如 "订单系统" 和 "支付系统" 不是同一项目
- 只要核心项目一致就算重复

## 输出格式
```json
{
  "duplicate": true或false,
  "matched_name": "匹配到的已有名称（如无则为空字符串）"
}
```

只返回 JSON。$PROMPT$, 'S6 项目树：项目名语义去重判定')

ON CONFLICT (key) DO UPDATE SET content = EXCLUDED.content, description = EXCLUDED.description;
