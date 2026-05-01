"""
LLM 工具函数 — 共享的 LLM 实例创建和 JSON 解析
"""
import json

from langchain_openai import ChatOpenAI
from backend.config import settings


def get_llm(temperature: float = 0.1, **kwargs) -> ChatOpenAI:
    """获取 DeepSeek LLM 实例"""
    return ChatOpenAI(
        model=settings.DEEPSEEK_MODEL,
        api_key=settings.DEEPSEEK_API_KEY,
        base_url=settings.DEEPSEEK_BASE_URL,
        temperature=temperature,
        **kwargs,
    )


def parse_llm_json(content: str) -> dict:
    """解析 LLM 返回的 JSON，处理 markdown 包裹和可能的截断"""
    content = content.strip()
    if "```json" in content:
        content = content.split("```json")[1]
        if "```" in content:
            content = content.split("```")[0]
        content = content.strip()
    elif "```" in content:
        content = content.split("```")[1]
        if "```" in content:
            content = content.split("```")[0]
        content = content.strip()

    try:
        return json.loads(content)
    except json.JSONDecodeError:
        # JSON 可能被截断，尝试补齐常见后缀
        for suffix in [']}}', ']}', '}}', '}', ']']:
            try:
                return json.loads(content + suffix)
            except json.JSONDecodeError:
                continue
        raise
