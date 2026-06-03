"""
Embedding 匹配 Skill — 将面试问题文本匹配到知识树叶子节点

Skill 定义：
- name: match_nearest_knowledge_node
- description: embed(text) -> pgvector 检索 top_k 候选 -> LLM rerank -> 返回最佳 node_id 或 None
- input: text (str), db (AsyncSession), top_k (int), threshold (float)
- output: int | None — 命中的 knowledge_node.id

匹配规则：
- 仅在 node_type='leaf' 且 embedding IS NOT NULL 的节点中检索
- 先用 pgvector 取 top_k 候选
- 然后让 LLM 判断哪个真正匹配（防止纯向量距离误判）
- 如果都不匹配，返回 None（由上层挂到「未分类」）
"""
from __future__ import annotations

import logging

from sqlalchemy import text as sql_text
from sqlalchemy.ext.asyncio import AsyncSession

from backend.services.embedding import get_embedding
from backend.services.llm import get_llm, parse_llm_json

logger = logging.getLogger(__name__)


RERANK_PROMPT = """你正在为面试问题匹配最合适的知识点。

## 面试问题
{text}

## 候选知识点（已按向量相似度排序）
{candidates}

## 规则
- 仔细判断面试问题真正考察的核心知识点
- 用户输入可能有口误/语音转写错误，按语义而非字面匹配
- 如果有明显匹配的候选，输出该候选的 id
- 如果所有候选都和问题考察点不符（哪怕距离很近），输出 null
- 例如：问"Redis 持久化" 但候选只有"Redis 数据结构"——输出 null

## 输出格式（JSON）
```json
{{"node_id": 123, "reason": "简要理由"}}
```
或
```json
{{"node_id": null, "reason": "都不匹配"}}
```

只返回 JSON。"""


async def match_nearest_knowledge_node(
    text: str,
    db: AsyncSession,
    top_k: int = 5,
    distance_threshold: float = 0.5,
) -> int | None:
    """
    匹配面试问题到知识树叶子节点。

    Args:
        text: 面试问题文本
        db: 异步数据库 session
        top_k: pgvector 召回数
        distance_threshold: 距离阈值（cosine distance, 越小越近），超过则不进 rerank

    Returns:
        匹配到的 knowledge_node.id，未匹配返回 None
    """
    text = (text or "").strip()
    if not text:
        return None

    # 1) 向量化
    vec = await get_embedding(text)
    if not vec:
        logger.warning("embedding 获取失败，跳过匹配")
        return None

    # 2) pgvector 召回 top_k
    # 注：pgvector 的 <=> 是 cosine distance（0 完全相同，2 完全相反）
    vec_str = "[" + ",".join(f"{x:.6f}" for x in vec) + "]"
    sql = sql_text(f"""
        SELECT id, name, (embedding <=> '{vec_str}'::vector) AS distance
        FROM knowledge_node
        WHERE node_type = 'leaf' AND embedding IS NOT NULL
        ORDER BY embedding <=> '{vec_str}'::vector
        LIMIT :k
    """)
    result = await db.execute(sql, {"k": top_k})
    rows = result.fetchall()
    if not rows:
        return None

    # 过滤距离过大的候选
    candidates = [(r.id, r.name, float(r.distance)) for r in rows if float(r.distance) <= distance_threshold]
    if not candidates:
        logger.info(f"无候选满足距离阈值 {distance_threshold}，原始最佳距离={float(rows[0].distance):.3f}")
        return None

    # 3) LLM rerank（不再走"单候选近距离直接返回"快路径——
    #    向量近不等于语义对，例如"MySQL/事务"和"分布式系统/事务"向量极近但语义不同，必须 LLM 兜一道）
    cand_lines = "\n".join(
        f"- id={cid}, name={cname} (距离={dist:.3f})" for cid, cname, dist in candidates
    )
    try:
        llm = get_llm(temperature=0.0, max_tokens=256)
        resp = await llm.ainvoke(RERANK_PROMPT.format(text=text[:500], candidates=cand_lines))
        data = parse_llm_json(resp.content)
    except Exception as e:
        logger.warning(f"rerank LLM 失败: {e}，降级返回最近的候选")
        # 距离非常近时（< 0.25）降级用最近的；否则放弃
        return candidates[0][0] if candidates[0][2] < 0.25 else None

    node_id = data.get("node_id") if data else None
    if node_id is None:
        return None
    # 确保 LLM 返回的 id 在候选里（防 LLM 编 id）
    valid_ids = {c[0] for c in candidates}
    if int(node_id) not in valid_ids:
        logger.warning(f"LLM 返回了非候选 id={node_id}，丢弃")
        return None
    return int(node_id)
