"""
Embedding 服务 — 用户回答向量化（DashScope）
用于 Agent 长期记忆：下次出题时 RAG 检索"用户上次怎么回答"
"""
import logging
import httpx
from backend.config import settings

logger = logging.getLogger(__name__)

DASHSCOPE_EMBEDDING_URL = "https://dashscope.aliyuncs.com/api/v1/services/embeddings/text-embedding/text-embedding"


async def get_embedding(text: str) -> list[float] | None:
    """调用 DashScope text-embedding-v3 获取 1024 维向量"""
    if not settings.DASHSCOPE_API_KEY:
        logger.warning("DASHSCOPE_API_KEY 未配置，跳过 embedding")
        return None

    try:
        async with httpx.AsyncClient(timeout=30) as client:
            resp = await client.post(
                DASHSCOPE_EMBEDDING_URL,
                headers={
                    "Authorization": f"Bearer {settings.DASHSCOPE_API_KEY}",
                    "Content-Type": "application/json",
                },
                json={
                    "model": "text-embedding-v3",
                    "input": {"texts": [text[:2000]]},  # 截断防超限
                    "parameters": {"dimension": 1024},
                },
            )
            data = resp.json()
            if "output" in data and "embeddings" in data["output"]:
                return data["output"]["embeddings"][0]["embedding"]
            else:
                logger.error(f"Embedding 响应异常: {data}")
                return None
    except Exception as e:
        logger.error(f"Embedding 调用失败: {e}")
        return None
