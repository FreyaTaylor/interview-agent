"""
LeetCode Skill — 根据题目描述获取 LeetCode 信息

Skill 定义：
- name: fetch_leetcode_info
- description: 从面试中提到的算法题描述，提取 LeetCode 标题/slug，并通过 LeetCode 中国站 GraphQL 接口获取链接、难度等信息
- input: question_text (str) — 面试官提到的算法题描述
- output: dict | None — {title, slug, url, difficulty} 或 None（不是 LeetCode 题或无法识别）

流程：
  1. LLM 提取题目名 + slug（LLM 训练数据里有绝大部分 LeetCode 题）
  2. 调 LeetCode 中国站 GraphQL 验证 slug + 拿 difficulty
  3. GraphQL 失败时降级：保留 LLM 给出的 url（不阻塞流程）
"""
from __future__ import annotations

import logging

import httpx

from backend.services.llm import get_llm, parse_llm_json

logger = logging.getLogger(__name__)

LEETCODE_GRAPHQL_URL = "https://leetcode.cn/graphql/"

# 提取题目 slug 的 prompt
EXTRACT_PROMPT = """从下面的面试对话片段中，识别面试官提到的 LeetCode 算法题，提取题目名称和对应的英文 slug。

## 面试片段
{text}

## 规则
- 如果片段中提到的题目是 LeetCode 上有的题目（常见算法题），输出 JSON
- slug 是英文小写、用连字符分隔，对应 leetcode.cn/problems/<slug>/ 中的部分
- 例如：「反转链表」→ slug "reverse-linked-list"；「两数之和」→ "two-sum"；「LRU 缓存」→ "lru-cache"
- 如果不是 LeetCode 上的题（如自定义算法题、系统设计题），输出 {{"is_leetcode": false}}
- 如果无法识别题目名，也输出 {{"is_leetcode": false}}

## 输出格式（JSON）
```json
{{"is_leetcode": true, "title": "反转链表", "slug": "reverse-linked-list"}}
```
或
```json
{{"is_leetcode": false}}
```

只返回 JSON，不要其他内容。"""


async def _query_leetcode_graphql(slug: str) -> dict | None:
    """调 LeetCode 中国站 GraphQL 验证 slug 并取 difficulty"""
    query = """
    query getQuestion($titleSlug: String!) {
        question(titleSlug: $titleSlug) {
            titleSlug
            title
            translatedTitle
            difficulty
        }
    }
    """
    try:
        async with httpx.AsyncClient(timeout=5.0) as client:
            resp = await client.post(
                LEETCODE_GRAPHQL_URL,
                json={"query": query, "variables": {"titleSlug": slug}},
                headers={
                    "User-Agent": "Mozilla/5.0",
                    "Content-Type": "application/json",
                    "Referer": f"https://leetcode.cn/problems/{slug}/",
                },
            )
            if resp.status_code != 200:
                return None
            data = resp.json().get("data", {}).get("question")
            if not data:
                return None
            return {
                "title": data.get("translatedTitle") or data.get("title"),
                "slug": data["titleSlug"],
                "difficulty": (data.get("difficulty") or "").lower() or None,
            }
    except Exception as e:
        logger.warning(f"LeetCode GraphQL 查询失败 slug={slug}: {e}")
        return None


async def fetch_leetcode_info(question_text: str) -> dict | None:
    """
    主入口：从面试题目描述获取 LeetCode 信息。

    Returns:
        dict | None — 命中时返回 {title, slug, url, difficulty}，未命中返回 None。
    """
    text = (question_text or "").strip()
    if not text:
        return None

    # 1) LLM 提取 slug
    try:
        llm = get_llm(temperature=0.0, max_tokens=256)
        prompt = EXTRACT_PROMPT.format(text=text[:1000])  # 截断防 prompt 过长
        resp = await llm.ainvoke(prompt)
        data = parse_llm_json(resp.content)
    except Exception as e:
        logger.warning(f"LeetCode slug 提取失败: {e}")
        return None

    if not data or not data.get("is_leetcode"):
        return None

    slug = (data.get("slug") or "").strip().lower()
    title = (data.get("title") or "").strip()
    if not slug:
        return None

    # 2) GraphQL 验证 + 补 difficulty
    verified = await _query_leetcode_graphql(slug)
    if verified:
        return {
            "title": verified["title"] or title,
            "slug": verified["slug"],
            "url": f"https://leetcode.cn/problems/{verified['slug']}/",
            "difficulty": verified["difficulty"],
        }

    # 3) GraphQL 失败时降级：用 LLM 给的 slug 拼 URL，不带 difficulty
    return {
        "title": title or slug,
        "slug": slug,
        "url": f"https://leetcode.cn/problems/{slug}/",
        "difficulty": None,
    }
