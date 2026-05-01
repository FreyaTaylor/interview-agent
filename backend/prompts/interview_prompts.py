"""
面试记录解析相关 Prompt
"""

INTERVIEW_PARSE_PROMPT = """你是一位资深的面试分析专家。请解析以下面试记录文本，提取面试官的提问和候选人的回答。

## 面试记录
{raw_text}

## 要求
1. **忽略非面试内容**：自言自语、打电话、闲聊、等待、面试官的过渡语（如"那我们聊下一个话题"、"你有什么想问的吗"、"今天大概就这些"）等无关内容直接跳过，不要当作提问
2. 识别出面试官的每一个提问（包括追问），同时提取候选人对该问题的回答要点
3. **聚类规则（核心）**：
   - 同一知识点的问题+追问归为一组
   - **追问一定属于前一个话题**：面试官的追问（如"那如果xxx呢？"、"OK那你刚刚说的xxx"、"这种情况你怎么处理？"）必须归入前一个知识点分组，绝不能独立分为 other
   - 判断是否为追问的线索：面试官用"那"、"OK那"、"你刚刚提到"、"这种情况"、"如果说"等引导词延续前一个话题
   - 面试官在某个话题下连续问的多个问题，即使措辞不同，只要围绕同一技术领域就归为一组
4. 分为5种类型：
   - **knowledge**: 技术知识点问题（八股文、原理、概念、锁、索引、事务、一致性等可标准化评分的问题）
   - **project**: 项目经验拷打（具体项目的设计、实现、踩坑等，没有标准答案）
   - **algorithm**: 算法/手撕代码题
   - **hr**: 行为/HR 面试题
   - **other**: 真正无法归类的问题（极少数，如面试官离题闲聊）
5. **other 类应极少使用**：如果一个问题涉及技术概念（锁、索引、事务、分布式一致性等），它就是 knowledge 类，不是 other。只有完全无法判断技术主题的提问才放 other
6. 对 knowledge 类：
   - knowledge_point: 具体知识点名称（如"Redis分布式锁"、"MySQL行锁与间隙锁"、"分布式事务一致性"）
   - category: 所属技术分类（如"分布式"、"Java基础"、"数据库"等）
7. 对 project 类：
   - project_name: 项目名称（如"订单系统"、"消息推送平台"）
   - topic: 拷打主题（如"超时取消方案"、"高并发设计"）
8. 对 algorithm 类，尝试匹配 LeetCode 题号
9. 每个分组都要提取 user_answer（回答摘要）和 original_dialogue（原始对话片段）
10. 用户输入可能含错别字（语音转写），按语义理解
11. 如果文本中没有明确的回答内容，user_answer 设为空字符串

请严格按以下 JSON 格式输出：
```json
{{
  "groups": [
    {{
      "type": "knowledge",
      "knowledge_point": "具体知识点名称",
      "category": "所属技术分类",
      "questions": ["问题1", "问题2（追问）"],
      "user_answer": "候选人的回答要点摘要",
      "original_dialogue": "面试官：xxx？\\n我：xxx"
    }},
    {{
      "type": "project",
      "project_name": "项目名称",
      "topic": "拷打主题",
      "questions": ["问题1", "追问"],
      "user_answer": "候选人回答摘要",
      "original_dialogue": "面试官：xxx？\\n我：xxx"
    }},
    {{
      "type": "algorithm",
      "title": "算法题名称",
      "leetcode_id": 题号或null,
      "questions": ["手撕xxx"],
      "user_answer": "",
      "original_dialogue": "面试官：手撕xxx"
    }},
    {{
      "type": "hr",
      "questions": ["HR问题"],
      "user_answer": "",
      "original_dialogue": "面试官：xxx？\\n我：xxx"
    }},
    {{
      "type": "other",
      "questions": ["其他问题"],
      "user_answer": "",
      "original_dialogue": "..."
    }}
  ],
  "summary": "本次面试概要（1-2句话）"
}}
```"""
