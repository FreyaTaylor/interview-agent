"""
LLM 工具函数 — 共享的 LLM 实例创建和 JSON 解析
"""
import json
import logging
import os
from typing import Any

from langchain_openai import ChatOpenAI
from langchain_core.callbacks import BaseCallbackHandler
from backend.config import settings

logger = logging.getLogger("backend.llm")

# 控制是否打印 LLM 原始请求/响应，默认开启；设 LLM_DEBUG=0 可关闭
_LLM_DEBUG = os.getenv("LLM_DEBUG", "1") not in ("0", "false", "False", "")


class LLMDebugCallback(BaseCallbackHandler):
    """打印 LLM 调用的原始 messages 和返回内容到日志"""

    def on_chat_model_start(self, serialized, messages, **kwargs: Any) -> None:
        try:
            # messages 是 List[List[BaseMessage]]，一般取第一个
            msg_list = messages[0] if messages else []
            lines = ["===== LLM REQUEST ====="]
            for m in msg_list:
                role = m.__class__.__name__
                content = getattr(m, "content", "")
                tool_calls = getattr(m, "tool_calls", None)
                lines.append(f"--- [{role}] ---")
                if content:
                    lines.append(str(content))
                if tool_calls:
                    lines.append(f"tool_calls: {tool_calls}")
            lines.append("===== END REQUEST =====")
            logger.info("\n".join(lines))
        except Exception as e:
            logger.warning(f"LLMDebugCallback on_chat_model_start 失败: {e}")

    def on_llm_end(self, response, **kwargs: Any) -> None:
        try:
            lines = ["===== LLM RESPONSE ====="]
            for gen_list in response.generations:
                for gen in gen_list:
                    text = getattr(gen, "text", "") or ""
                    msg = getattr(gen, "message", None)
                    tool_calls = getattr(msg, "tool_calls", None) if msg else None
                    if text:
                        lines.append(text)
                    if tool_calls:
                        lines.append(f"tool_calls: {tool_calls}")
            lines.append("===== END RESPONSE =====")
            logger.info("\n".join(lines))
        except Exception as e:
            logger.warning(f"LLMDebugCallback on_llm_end 失败: {e}")


def get_llm(temperature: float = 0.1, **kwargs) -> ChatOpenAI:
    """获取 DeepSeek LLM 实例"""
    callbacks = kwargs.pop("callbacks", None) or []
    if _LLM_DEBUG:
        callbacks = [LLMDebugCallback(), *callbacks]
    return ChatOpenAI(
        model=settings.DEEPSEEK_MODEL,
        api_key=settings.DEEPSEEK_API_KEY,
        base_url=settings.DEEPSEEK_BASE_URL,
        temperature=temperature,
        callbacks=callbacks,
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
        # 字符串值内可能包含未转义的 ASCII 双引号，按错误位置迭代转义后重试
        salvaged = content
        for _ in range(50):
            try:
                return json.loads(salvaged)
            except json.JSONDecodeError as e:
                pos = e.pos
                if pos <= 0 or pos >= len(salvaged) or salvaged[pos] != '"':
                    break
                # 在该位置前插入反斜杠转义
                salvaged = salvaged[:pos] + '\\' + salvaged[pos:]
        raise
