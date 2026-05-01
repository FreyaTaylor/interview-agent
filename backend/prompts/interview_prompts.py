"""
面试记录解析相关 Prompt
"""

INTERVIEW_PARSE_PROMPT = """你是一位资深的面试分析专家。请解析以下面试记录文本，提取面试官的提问和候选人的回答。

## 面试记录
{raw_text}

## 要求
1. **忽略非面试内容**：自言自语、打电话、闲聊、等待等无关内容直接跳过
2. 识别出面试官的每一个提问（包括追问），同时提取候选人对该问题的回答要点
3. 将所有提问聚类：同一知识点的问题+追问归为一组
4. 分为5种类型：
   - **knowledge**: 技术知识点问题（八股文、原理、概念等可标准化评分的问题）
   - **project**: 项目经验拷打（具体项目的设计、实现、踩坑等，没有标准答案）
   - **algorithm**: 算法/手撕代码题
   - **hr**: 行为/HR 面试题
   - **other**: 无法归类的其他问题
5. 对 knowledge 类：
   - knowledge_point: 具体知识点名称（如"Redis分布式锁"、"Kafka消息丢失"）
   - category: 所属技术分类（如"分布式"、"Java基础"、"数据库"等）
6. 对 project 类：
   - project_name: 项目名称（如"订单系统"、"消息推送平台"）
   - topic: 拷打主题（如"超时取消方案"、"高并发设计"）
7. 对 algorithm 类，尝试匹配 LeetCode 题号
8. 每个分组都要提取 user_answer（回答摘要）和 original_dialogue（原始对话片段）
9. 用户输入可能含错别字（语音转写），按语义理解
10. 如果文本中没有明确的回答内容，user_answer 设为空字符串

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
