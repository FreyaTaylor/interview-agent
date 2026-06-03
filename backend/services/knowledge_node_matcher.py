"""
知识树匹配器 — 把面试问题挂到 knowledge_node 树

流程：
  1) embed(text) → 一次 pgvector 召回，同时取叶子 top_k 与类别 top_k
  2) 走 LLM rerank：要么命中已有叶子，要么由 LLM 在候选类别下"建议"一个新叶子名
  3) 若 LLM 建议建新叶子 → 写入 embedding 并 commit，返回新 id
  4) 都不行 → None（由上层挂到「未分类」）

与 backend/skills/embedding_match_skill 的区别：
- skill 是只读纯函数（仅返回现有叶子 id）
- 本服务负责"匹配 + 必要时创建"的副作用，对标 project_node_matcher
"""
from __future__ import annotations

import logging

from sqlalchemy import select, text as sql_text
from sqlalchemy.ext.asyncio import AsyncSession

from backend.models.knowledge import KnowledgeNode
from backend.services.embedding import get_embedding
from backend.services.llm import get_llm, parse_llm_json

logger = logging.getLogger(__name__)


# 召回参数
TOP_K_LEAF = 5
TOP_K_CATEGORY = 5
LEAF_DISTANCE_MAX = 0.5      # 叶子候选距离上限
CATEGORY_DISTANCE_MAX = 0.6  # 类别候选距离上限（略松，给"挂在哪个类目下"留空间）
FALLBACK_DISTANCE = 0.25     # LLM 失败时直接采用最近叶子的距离阈值


MATCH_OR_CREATE_PROMPT = """你正在为面试问题挂到合适的知识树节点上。

## 面试问题（知识点标签）
{text}

## 候选叶子（可直接命中，按向量距离从近到远）
{leaves}

## 候选类别（叶子都不合适时，挑一个最合适的类别作为新叶子的父节点）
{categories}

## 决策规则
1. 优先看候选叶子：如果有叶子的语义就是这个面试问题考察的核心知识点 → 输出 `matched_leaf_id`
2. 如果所有叶子都不对（哪怕距离很近，例如"MySQL/事务" 与 "分布式系统/事务" 是不同语境）→ 进入步骤 3
3. 从候选类别里挑一个最匹配的作为父节点，并给出一个**完整、自带技术域前缀**的新叶子名（如「MySQL MVCC」而非「MVCC」）
4. 如果连合适的类别都没有 → 全部输出 null

## 输出格式（严格 JSON，三选一）
命中已有叶子：
```json
{{"matched_leaf_id": 123, "suggested_parent_id": null, "suggested_leaf_name": null, "reason": "简要理由"}}
```
建新叶子：
```json
{{"matched_leaf_id": null, "suggested_parent_id": 27, "suggested_leaf_name": "MySQL MVCC", "reason": "简要理由"}}
```
都不行：
```json
{{"matched_leaf_id": null, "suggested_parent_id": null, "suggested_leaf_name": null, "reason": "简要理由"}}
```

只返回 JSON。"""


async def match_or_create_knowledge_node(
    text: str,
    db: AsyncSession,
) -> int | None:
    """匹配面试问题到知识树叶子；匹配不到则在合适的类别下自动新建叶子并返回 id。"""
    text = (text or "").strip()
    if not text:
        return None

    # 1) 向量化
    vec = await get_embedding(text)
    if not vec:
        logger.warning("embedding 获取失败，跳过匹配")
        return None
    vec_str = "[" + ",".join(f"{x:.6f}" for x in vec) + "]"

    # 2) 一次 SQL 召回叶子 + 类别（合并查询，按距离排）
    sql = sql_text(f"""
        SELECT id, name, node_type, parent_id, level,
               (embedding <=> '{vec_str}'::vector) AS distance
        FROM knowledge_node
        WHERE embedding IS NOT NULL
        ORDER BY embedding <=> '{vec_str}'::vector
        LIMIT :k
    """)
    rows = (await db.execute(sql, {"k": (TOP_K_LEAF + TOP_K_CATEGORY) * 2})).fetchall()
    if not rows:
        return None

    leaves = [r for r in rows if r.node_type == "leaf" and float(r.distance) <= LEAF_DISTANCE_MAX][:TOP_K_LEAF]
    cats = [r for r in rows if r.node_type != "leaf" and float(r.distance) <= CATEGORY_DISTANCE_MAX][:TOP_K_CATEGORY]

    if not leaves and not cats:
        logger.info(f"知识树召回为空：'{text[:60]}' 原始最近距离={float(rows[0].distance):.3f}")
        return None

    # 3) 构建路径（一次加载所有节点，按邻接表走父链）
    all_nodes = {n.id: n for n in (await db.execute(select(KnowledgeNode))).scalars().all()}

    def build_path(node_id: int) -> str:
        parts: list[str] = []
        cur = all_nodes.get(node_id)
        while cur is not None:
            parts.append(cur.name or "")
            cur = all_nodes.get(cur.parent_id) if cur.parent_id else None
        return " / ".join(reversed([p for p in parts if p]))

    leaf_lines = "\n".join(
        f"- leaf_id={r.id}: {build_path(r.id)} (距离={float(r.distance):.3f})" for r in leaves
    ) or "（无）"
    cat_lines = "\n".join(
        f"- category_id={r.id}: {build_path(r.id)} (距离={float(r.distance):.3f})" for r in cats
    ) or "（无）"

    # 4) LLM rerank + 建议
    try:
        llm = get_llm(temperature=0.0, max_tokens=320)
        resp = await llm.ainvoke(MATCH_OR_CREATE_PROMPT.format(
            text=text[:500], leaves=leaf_lines, categories=cat_lines,
        ))
        data = parse_llm_json(resp.content) or {}
    except Exception as e:
        logger.warning(f"知识匹配 LLM 失败: {e}，降级取最近叶子")
        if leaves and float(leaves[0].distance) < FALLBACK_DISTANCE:
            return leaves[0].id
        return None

    # 5) 决策分支
    matched_leaf_id = data.get("matched_leaf_id")
    if matched_leaf_id is not None:
        leaf_ids = {r.id for r in leaves}
        if int(matched_leaf_id) in leaf_ids:
            return int(matched_leaf_id)
        logger.warning(f"LLM 返回的 matched_leaf_id={matched_leaf_id} 不在候选里，丢弃")

    parent_id = data.get("suggested_parent_id")
    new_name = (data.get("suggested_leaf_name") or "").strip()
    if parent_id is not None and new_name:
        valid_parent_ids = {r.id for r in cats}
        if int(parent_id) not in valid_parent_ids:
            logger.warning(f"LLM 给的 suggested_parent_id={parent_id} 不在类别候选里，丢弃")
            return None
        parent = all_nodes.get(int(parent_id))
        if parent is None or parent.node_type == "leaf":
            # parent 必须是类别节点
            logger.warning(f"suggested_parent_id={parent_id} 不是有效类别节点")
            return None
        return await _create_leaf_under(db, parent, new_name)

    # 6) 兜底：LLM 明确放弃
    logger.info(f"LLM 判定无匹配且无可挂类别：'{text[:60]}' reason={data.get('reason')}")
    return None


async def _create_leaf_under(
    db: AsyncSession,
    parent: KnowledgeNode,
    leaf_name: str,
) -> int | None:
    """在指定类别下创建一个新叶子（带 embedding），返回新节点 id。"""
    # 用 父路径 / 叶子名 作为 embedding 文本，保持与现有节点一致
    parent_path_parts: list[str] = []
    cur: KnowledgeNode | None = parent
    while cur is not None:
        parent_path_parts.append(cur.name or "")
        if cur.parent_id is None:
            break
        cur = await db.get(KnowledgeNode, cur.parent_id)
    parent_path = " / ".join(reversed([p for p in parent_path_parts if p]))
    embed_text = f"{parent_path} / {leaf_name}" if parent_path else leaf_name

    try:
        new_vec = await get_embedding(embed_text)
    except Exception as e:
        logger.warning(f"新叶子 embedding 失败 '{leaf_name}': {e}")
        new_vec = None

    new_node = KnowledgeNode(
        parent_id=parent.id,
        name=leaf_name,
        level=(parent.level or 0) + 1,
        node_type="leaf",
        is_user_created=False,
        embedding=new_vec,
    )
    db.add(new_node)
    await db.commit()
    await db.refresh(new_node)
    logger.info(f"自动创建知识叶子 id={new_node.id} '{leaf_name}' 挂在 [{parent_path}]")
    return new_node.id
