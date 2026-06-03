"""
项目节点 3 级匹配器 — 把面试解析出的项目题挂到 project_node 树

3 步流程：
  1) match_or_create_project_root(project_name)
     - 精确名匹配 → LLM 语义匹配 → 都失败用「未命名项目」
  2) match_or_create_topic(root_id, topic_name)
     - root 下精确名匹配 → LLM 语义匹配 → 都失败新建 level=2
  3) match_or_create_question(topic_id, question_text, threshold)
     - embedding 余弦相似度匹配现有 leaf；命中则 name 拼接 ("已有 \\ 新")
     - 未命中则新建 level=3 leaf 并写入 embedding
"""
from __future__ import annotations

import logging

from sqlalchemy import select, text as sql_text
from sqlalchemy.ext.asyncio import AsyncSession

from backend.models.project_node import ProjectNode
from backend.services._db_utils import get_or_create
from backend.services.embedding import get_embedding
from backend.services.llm import get_llm, parse_llm_json

logger = logging.getLogger(__name__)

UNNAMED_PROJECT = "未命名项目"
QUESTION_SIM_THRESHOLD = 0.85  # 余弦相似度阈值（pgvector 用距离: 1 - distance >= 0.85 ⇒ distance <= 0.15）
QUESTION_DISTANCE_MAX = 1.0 - QUESTION_SIM_THRESHOLD


# ============================================================
# Step 1: 项目根
# ============================================================

async def match_or_create_project_root(db: AsyncSession, llm_project_name: str) -> int:
    """匹配/创建 level=1 项目根节点，返回 node.id。"""
    name = (llm_project_name or "").strip()
    if not name:
        return await _get_or_create_unnamed_root(db)

    # 所有项目根（包含「未命名项目」）
    roots = (await db.execute(
        select(ProjectNode).where(ProjectNode.user_id == 1, ProjectNode.level == 1)
    )).scalars().all()

    # 1) 精确匹配
    norm = name.lower().replace(" ", "")
    for r in roots:
        if (r.name or "").lower().replace(" ", "") == norm:
            return r.id

    # 2) LLM 语义匹配（候选过滤掉所有「未命名项目-N」自身，它们本就是占位）
    candidates = [r for r in roots if not (r.name or "").startswith(UNNAMED_PROJECT)]
    if candidates:
        catalog = "\n".join(f"- id={r.id}, name={r.name}" for r in candidates)
        prompt = f"""候选人真实项目列表：
{catalog}

面试中 AI 提取的项目名：「{name}」

判断这个名字是否对应列表中的某个项目（语义匹配，允许名字不完全相同）。
- 对应某个项目 → 返回该项目的 id
- 都不对应（AI 编造、描述模糊、明显不同的项目）→ 返回 null

只输出 JSON：
```json
{{"id": 123}}
```
"""
        try:
            resp = await get_llm(temperature=0.0, max_tokens=128).ainvoke(prompt)
            data = parse_llm_json(resp.content) or {}
            pid = data.get("id")
            if pid is not None:
                pid = int(pid)
                if pid in {r.id for r in candidates}:
                    return pid
        except Exception as e:
            logger.warning(f"项目根 LLM 匹配失败 name={name}: {e}")

    # 3) 都失败 → 未命名项目
    return await _get_or_create_unnamed_root(db)


async def _get_or_create_unnamed_root(db: AsyncSession) -> int:
    """复用全局唯一的「未命名项目」根节点（与「未命名知识点」一致的策略）。

    所有未匹配上真实项目的题目都挂到这棵树下；具体项目名由调用方作为 level=2
    话题保留，便于管理后台手动拖到正确项目下。
    """
    node = await get_or_create(
        db, ProjectNode,
        filter_by={"user_id": 1, "level": 1, "name": UNNAMED_PROJECT},
        defaults={"parent_id": None, "node_type": "category", "sort_order": 9999},
    )
    return node.id


# ============================================================
# Step 2: 话题（LLM 匹配）
# ============================================================

async def match_or_create_topic(db: AsyncSession, root_id: int, llm_topic: str) -> int:
    """在指定项目根下匹配/创建 level=2 话题节点。"""
    topic = (llm_topic or "").strip() or "通用"

    siblings = (await db.execute(
        select(ProjectNode).where(
            ProjectNode.parent_id == root_id, ProjectNode.level == 2,
        )
    )).scalars().all()

    # 1) 精确匹配
    norm = topic.lower().replace(" ", "")
    for s in siblings:
        if (s.name or "").lower().replace(" ", "") == norm:
            return s.id

    # 2) LLM 语义匹配
    if siblings:
        catalog = "\n".join(f"- id={s.id}, name={s.name}" for s in siblings)
        prompt = f"""现有话题分类：
{catalog}

面试中 AI 提取的话题：「{topic}」

判断是否对应某个现有话题（语义匹配，允许名字不完全相同）。
- 对应某个话题 → 返回该话题的 id
- 都不对应 → 返回 null

只输出 JSON：
```json
{{"id": 12}}
```
"""
        try:
            resp = await get_llm(temperature=0.0, max_tokens=128).ainvoke(prompt)
            data = parse_llm_json(resp.content) or {}
            tid = data.get("id")
            if tid is not None:
                tid = int(tid)
                if tid in {s.id for s in siblings}:
                    return tid
        except Exception as e:
            logger.warning(f"话题 LLM 匹配失败 topic={topic}: {e}")

    # 3) 新建
    new_node = ProjectNode(
        parent_id=root_id, name=topic,
        level=2, node_type="category", sort_order=0,
    )
    db.add(new_node)
    await db.flush()
    return new_node.id


# ============================================================
# Step 3: 问题（embedding 匹配）
# ============================================================

async def match_or_create_question(
    db: AsyncSession,
    topic_id: int,
    question_text: str,
    threshold: float = QUESTION_SIM_THRESHOLD,
) -> tuple[int, bool]:
    """
    匹配/创建 level=3 问题叶子节点。
    返回 (leaf_id, is_new)。命中时 name 累积："已有 \\ 新表述"。
    """
    text = (question_text or "").strip()
    if not text:
        # 兜底：用占位文本，避免空叶子
        text = "(无题目内容)"

    vec = await get_embedding(text)
    if not vec:
        # embedding 失败 → 退化为精确名匹配
        return await _fallback_exact_match(db, topic_id, text)

    distance_max = 1.0 - threshold
    vec_str = "[" + ",".join(f"{x:.6f}" for x in vec) + "]"

    # 在该 topic 下找最相似的 leaf
    sql = sql_text(f"""
        SELECT id, name, (embedding <=> '{vec_str}'::vector) AS distance
        FROM project_node
        WHERE parent_id = :tid
          AND node_type = 'leaf'
          AND embedding IS NOT NULL
        ORDER BY embedding <=> '{vec_str}'::vector
        LIMIT 1
    """)
    row = (await db.execute(sql, {"tid": topic_id})).fetchone()

    if row and float(row.distance) <= distance_max:
        # 命中：累积表述（用 " \ " 分隔，避免重复添加同一文本）
        existing = await db.get(ProjectNode, row.id)
        if existing and text not in (existing.name or ""):
            existing.name = f"{existing.name} \\ {text}"
        return row.id, False

    # 未命中：新建 leaf
    new_leaf = ProjectNode(
        parent_id=topic_id, name=text,
        level=3, node_type="leaf", sort_order=0,
        embedding=vec,
    )
    db.add(new_leaf)
    await db.flush()
    return new_leaf.id, True


async def _fallback_exact_match(
    db: AsyncSession, topic_id: int, text: str,
) -> tuple[int, bool]:
    """embedding 不可用时的兜底：精确名匹配，否则新建（无 embedding）。"""
    siblings = (await db.execute(
        select(ProjectNode).where(
            ProjectNode.parent_id == topic_id, ProjectNode.level == 3,
        )
    )).scalars().all()
    for s in siblings:
        if (s.name or "").strip() == text:
            return s.id, False
    new_leaf = ProjectNode(
        parent_id=topic_id, name=text,
        level=3, node_type="leaf", sort_order=0,
    )
    db.add(new_leaf)
    await db.flush()
    return new_leaf.id, True


# ============================================================
# 3 级统一入口（外部调用）
# ============================================================

async def link_question_to_tree(
    db: AsyncSession,
    project_name: str,
    topic: str,
    question_text: str,
) -> int:
    """一站式：把一条面试题挂到 project_node 树，返回 leaf 节点 id。"""
    root_id = await match_or_create_project_root(db, project_name)
    topic_id = await match_or_create_topic(db, root_id, topic)
    leaf_id, _ = await match_or_create_question(db, topic_id, question_text)
    return leaf_id
