"""
面试记录解析相关 Prompt
"""

# ---- 面试文本解析：提取问答对 + 分类 + 聚类 ----
INTERVIEW_PARSE_PROMPT = """你是一位资深的面试分析专家。请解析以下面试记录文本，提取面试官的提问和候选人的回答。

## 面试记录
{raw_text}

## 要求
1. 识别出面试官的每一个提问（包括追问），同时提取候选人对该问题的回答要点
2. 将所有提问聚类：同一知识点的问题+追问归为一组
3. 分类：
   - knowledge: 技术知识点问题
   - algorithm: 算法/手撕代码题
   - hr: 行为/HR 面试题
4. 对 knowledge 类，给出知识点名称（如"Redis分布式锁"、"HashMap原理"），并提取候选人的回答摘要
5. 对 algorithm 类，尝试匹配 LeetCode 题号
6. 用户输入可能含错别字（语音转写），按语义理解
7. 如果文本中没有明确的回答内容，user_answer 设为空字符串

请严格按以下 JSON 格式输出：
```json
{{
  "groups": [
    {{
      "type": "knowledge",
      "knowledge_point": "知识点名称",
      "questions": ["问题1", "问题2（追问）"],
      "user_answer": "候选人的回答要点摘要（合并该组所有回答）"
    }},
    {{
      "type": "algorithm",
      "title": "算法题名称",
      "leetcode_id": 题号或null,
      "questions": ["手撕xxx"],
      "user_answer": ""
    }},
    {{
      "type": "hr",
      "questions": ["HR问题"],
      "user_answer": ""
    }}
  ],
  "summary": "本次面试概要（1-2句话）"
}}
```"""
