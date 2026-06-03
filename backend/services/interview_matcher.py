"""
面试分组 → 知识树/项目树节点匹配

- knowledge 类：用 embedding skill 找最近的 knowledge_node 叶子（带 LLM rerank）；
  未命中时在「未命名知识点」根下新建叶子作为占位，便于管理后台后续归位
- project   类：3 级匹配挂到 project_node 树（项目根 → 话题 → 问题叶子）
"""
import logging

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from backend.models.knowledge import KnowledgeNode
from backend.models.project_node import ProjectNode
from backend.services._db_utils import get_or_create
from backend.services.embedding import get_embedding
from backend.services.project_node_matcher import (
    match_or_create_project_root,
    match_or_create_topic,
    match_or_create_question,
)
from backend.skills.embedding_match_skill import match_nearest_knowledge_node

logger = logging.getLogger(__name__)

UNNAMED_KNOWLEDGE = "未命名知识点"


async def _get_or_create_unnamed_knowledge_root(db: AsyncSession) -> int:
    """懒创建/复用「未命名知识点」根节点（level=1, category）。"""
    node = await get_or_create(
        db, KnowledgeNode,
        filter_by={"level": 1, "name": UNNAMED_KNOWLEDGE},
        defaults={"parent_id": None, "node_type": "category",
                  "sort_order": 9999, "is_user_created": False},
    )
    return node.id


async def _create_orphan_leaf(db: AsyncSession, name: str) -> int:
    """在「未命名知识点」根下新建一个叶子；同名则复用，避免同次面试重复创建。"""
    root_id = await _get_or_create_unnamed_knowledge_root(db)
    existing = (await db.execute(
        select(KnowledgeNode).where(
            KnowledgeNode.parent_id == root_id,
            KnowledgeNode.name == name,
        )
    )).scalar_one_or_none()
    if existing:
        return existing.id
    emb = None
    try:
        emb = await get_embedding(name)
    except Exception as e:
        logger.warning(f"未命名知识点 embedding 失败 name={name}: {e}")
    leaf = KnowledgeNode(
        parent_id=root_id, name=name,
        level=2, node_type="leaf", sort_order=9999,
        is_user_created=False,
        embedding=emb,
    )
    db.add(leaf)
    await db.flush()
    return leaf.id


async def match_nodes(groups: list[dict], db: AsyncSession) -> list[dict]:
    """给每个 group 补 matched_node_id / matched_project_id。

    knowledge：先用 embedding skill 在已有叶子里匹配；命中即返回；未命中则在
              「未命名知识点」根下新建一个同名叶子（占位），matched_node_id 指向它，
              便于管理后台手动拖到正确分类下。
    project ：3 级匹配挂到 project_node 树（项目根 → 话题 → 问题叶子）。
    """
    enriched: list[dict] = []
    for g in groups:
        g = dict(g)
        if g.get("type") == "knowledge":
            kp = (g.get("knowledge_point") or "").strip()
            node_id = await match_nearest_knowledge_node(kp, db) if kp else None
            if not node_id and kp:
                # 未命中 → 在「未命名知识点」下新建占位叶子
                try:
                    node_id = await _create_orphan_leaf(db, kp)
                    logger.info(f"知识点未匹配，在「未命名知识点」下创建占位叶子: {kp} → id={node_id}")
                except Exception as e:
                    logger.warning(f"创建未命名知识点叶子失败 kp={kp}: {e}")
                    node_id = None
            g["matched_node_id"] = node_id
            g["matched_node_name"] = None
            if node_id:
                node = await db.get(KnowledgeNode, node_id)
                g["matched_node_name"] = node.name if node else None
        elif g.get("type") == "project":
            # 3 级匹配：项目根 → 话题 → 问题叶子
            project_name = g.get("project_name", "")
            topic = g.get("topic", "") or "通用"
            questions = g.get("questions") or []
            main_question = questions[0] if questions else ""
            try:
                root_id = await match_or_create_project_root(db, project_name)
                topic_id = await match_or_create_topic(db, root_id, topic)
                leaf_id, _ = await match_or_create_question(db, topic_id, main_question)
                g["matched_project_id"] = leaf_id  # 指向叶子（问题节点）
                leaf = await db.get(ProjectNode, leaf_id)
                g["matched_project_name"] = leaf.name if leaf else None
            except Exception as e:
                logger.warning(f"项目题匹配失败 name={project_name} topic={topic}: {e}")
                g["matched_project_id"] = None
                g["matched_project_name"] = None
        # algorithm/hr/other 不需要匹配节点 — 走独立聚合表
        enriched.append(g)
    return enriched
