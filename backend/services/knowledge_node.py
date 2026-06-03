"""
知识节点服务 — 知识树节点的 CRUD 操作

职责：
  1. 获取完整知识树节点列表（编辑用）
  2. 创建节点（自动推断 level / node_type）
  3. 更新节点（支持改名、改权重、移动父节点、改排序）
  4. 批量更新排序
  5. 递归删除节点（含子孙 + 关联的讲解内容和对话记录）
"""
import logging

from sqlalchemy import select, update as sql_update, delete as sql_delete
from sqlalchemy.ext.asyncio import AsyncSession

from backend.models.knowledge import KnowledgeNode
from backend.models.learn import KnowledgeContent, LearnChat
from backend.models.interview import InterviewKnowledgeQuestion, UserAnswerEmbedding
from backend.services import qa_aggregate

logger = logging.getLogger(__name__)


# ========== 知识树（带掌握度） ==========

async def build_knowledge_tree(db: AsyncSession) -> list[dict]:
    """
    构建完整知识树，附带每个节点的掌握度信息。
    返回嵌套的树形字典列表（roots），每个节点含 children。
    """
    stmt = select(KnowledgeNode).order_by(
        KnowledgeNode.level, KnowledgeNode.sort_order, KnowledgeNode.id,
    )
    result = await db.execute(stmt)
    all_nodes = result.scalars().all()

    # 掌握度由 question_attempt 派生；批量取叶子节点的 mastery
    leaf_ids = [n.id for n in all_nodes if n.node_type == "leaf"]
    mastery_map = await qa_aggregate.get_kp_mastery_map(db, leaf_ids)

    # 构建节点字典
    node_map: dict[int, dict] = {}
    for n in all_nodes:
        mastery, study_count = mastery_map.get(n.id, (0, 0))
        node_map[n.id] = {
            "id": n.id, "parent_id": n.parent_id, "name": n.name,
            "level": n.level, "node_type": n.node_type,
            "interview_weight": n.interview_weight, "sort_order": n.sort_order,
            "mastery_level": mastery,
            "study_count": study_count,
            "children": [],
        }

    # 组装树
    roots = []
    for n in all_nodes:
        nd = node_map[n.id]
        if n.parent_id and n.parent_id in node_map:
            node_map[n.parent_id]["children"].append(nd)
        else:
            roots.append(nd)

    return roots


# ========== 查询 ==========

async def get_all_nodes(db: AsyncSession) -> list[dict]:
    """
    获取全部知识节点，按 level → sort_order → id 排序。
    返回字典列表，供前端渲染编辑用树。
    """
    stmt = select(KnowledgeNode).order_by(
        KnowledgeNode.level, KnowledgeNode.sort_order, KnowledgeNode.id,
    )
    result = await db.execute(stmt)
    return [{
        "id": n.id,
        "parent_id": n.parent_id,
        "name": n.name,
        "level": n.level,
        "node_type": n.node_type,
        "interview_weight": n.interview_weight,
        "sort_order": n.sort_order,
    } for n in result.scalars().all()]


# ========== 创建 ==========

async def create_node(
    db: AsyncSession,
    parent_id: int | None,
    name: str,
    interview_weight: int = 3,
) -> dict:
    """
    新增知识节点。
    - 有 parent_id → level = 父节点 level + 1，level >= 3 判定为 leaf
    - 无 parent_id → 一级分类
    父节点不存在时抛 ValueError。
    """
    if parent_id:
        parent = await db.get(KnowledgeNode, parent_id)
        if not parent:
            raise ValueError("父节点不存在")
        level = parent.level + 1
        node_type = "leaf" if level >= 3 else "category"
    else:
        level = 1
        node_type = "category"

    node = KnowledgeNode(
        parent_id=parent_id,
        name=name.strip(),
        level=level,
        node_type=node_type,
        interview_weight=interview_weight,
    )
    db.add(node)
    await db.commit()
    return {"id": node.id, "name": node.name, "level": level}


# ========== 更新 ==========

async def update_node(
    db: AsyncSession,
    node_id: int,
    *,
    name: str | None = None,
    interview_weight: int | None = None,
    parent_id: int | None = None,
    sort_order: int | None = None,
    move_parent: bool = False,
) -> dict:
    """
    更新知识节点字段。
    move_parent=True 时会重新计算 level 和 node_type。
    节点不存在时抛 ValueError。
    """
    node = await db.get(KnowledgeNode, node_id)
    if not node:
        raise ValueError("节点不存在")

    if name is not None:
        node.name = name.strip()
    if interview_weight is not None:
        node.interview_weight = interview_weight
    if sort_order is not None:
        node.sort_order = sort_order

    # 移动到新父节点：重算层级
    if move_parent:
        node.parent_id = parent_id
        if parent_id:
            parent = await db.get(KnowledgeNode, parent_id)
            if parent:
                node.level = parent.level + 1
        else:
            node.level = 1
        node.node_type = "leaf" if node.level >= 3 else "category"

    await db.commit()
    return {"id": node.id, "name": node.name}


async def batch_update_sort(
    db: AsyncSession,
    updates: list[dict],
) -> int:
    """
    批量更新节点排序。
    updates: [{"id": 1, "sort_order": 0}, ...]
    返回实际更新的条数。
    """
    count = 0
    for item in updates:
        node = await db.get(KnowledgeNode, item["id"])
        if node:
            node.sort_order = item["sort_order"]
            count += 1
    await db.commit()
    return count


# ========== 删除 ==========

async def delete_node_recursive(db: AsyncSession, node_id: int) -> int:
    """
    递归删除节点及其所有子孙。
    同时清理关联的讲解内容（KnowledgeContent）和对话记录（LearnChat）。
    删除后，如果父节点不再有子节点，将其 node_type 改为 leaf。
    节点不存在时抛 ValueError。
    返回被删除的节点 ID。
    """
    node = await db.get(KnowledgeNode, node_id)
    if not node:
        raise ValueError("节点不存在")

    # 1. 收集所有要删除的节点 ID（自身 + 子孙）
    # 注意：这里只收集，不做 delete；否则会触发 autoflush，导致在 FK 置空前先删节点而报错。
    all_ids = [node.id]

    async def _collect_descendant_ids(pid: int) -> None:
        children = await db.execute(
            select(KnowledgeNode.id).where(KnowledgeNode.parent_id == pid)
        )
        child_ids = children.scalars().all()
        for cid in child_ids:
            all_ids.append(cid)
            await _collect_descendant_ids(cid)

    await _collect_descendant_ids(node.id)

    # 2. 清理关联数据
    await db.execute(sql_delete(LearnChat).where(LearnChat.knowledge_point_id.in_(all_ids)))
    await db.execute(sql_delete(KnowledgeContent).where(KnowledgeContent.knowledge_point_id.in_(all_ids)))
    # 面试相关引用：FK 没有 ON DELETE SET NULL，这里手动置空避免 FK 阻断删除
    await db.execute(
        sql_update(InterviewKnowledgeQuestion)
        .where(InterviewKnowledgeQuestion.knowledge_node_id.in_(all_ids))
        .values(knowledge_node_id=None)
    )
    await db.execute(
        sql_update(UserAnswerEmbedding)
        .where(UserAnswerEmbedding.knowledge_point_id.in_(all_ids))
        .values(knowledge_point_id=None)
    )

    # 3. 批量删除节点（自身 + 子孙）
    parent_id = node.parent_id
    await db.execute(sql_delete(KnowledgeNode).where(KnowledgeNode.id.in_(all_ids)))

    # 4. 父节点如果没有剩余子节点，变成 leaf
    if parent_id:
        remaining = await db.execute(
            select(KnowledgeNode).where(KnowledgeNode.parent_id == parent_id).limit(1)
        )
        if not remaining.scalar_one_or_none():
            parent = await db.get(KnowledgeNode, parent_id)
            if parent:
                parent.node_type = "leaf"

    await db.commit()
    return node_id
