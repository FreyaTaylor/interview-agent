"""
面试记录解析相关 Prompt
"""

# ---- 面试文本解析：提取问答对 + 分类 + 聚类 ----
INTERVIEW_PARSE_PROMPT = """你是一位资深的面试分析专家。请解析以下面试记录文本，提取所有面试官的提问。

## 面试记录
{raw_text}

## 要求
1. 识别出面试官的每一个提问（包括追问）
2. 将所有提问聚类：同一知识点的问题+追问归为一组
3. 分类：
   - knowledge: 技术知识点问题
   - algorithm: 算法/手撕代码题
   - hr: 行为/HR 面试题
4. 对 knowledge 类，给出知识点名称（如"Redis分布式锁"、"HashMap原理"）
5. 对 algorithm 类，尝试匹配 LeetCode 题号
6. 用户输入可能含错别字（语音转写），按语义理解

请严格按以下 JSON 格式输出：
```json
{{
  "groups": [
    {{
      "type": "knowledge",
      "knowledge_point": "知识点名称",
      "questions": ["问题1", "问题2（追问）", "问题3（追问）"]
    }},
    {{
      "type": "algorithm",
      "title": "算法题名称",
      "leetcode_id": 题号或null,
      "questions": ["手撕xxx"]
    }},
    {{
      "type": "hr",
      "questions": ["HR问题"]
    }}
  ],
  "summary": "本次面试概要（1-2句话）"
}}
```"""
