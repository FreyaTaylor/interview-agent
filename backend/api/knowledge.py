"""
知识树 API — 查看
"""
from fastapi import APIRouter, Depends
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from backend.database import get_db
from backend.models.knowledge import KnowledgeNode
from backend.models.study import MasteryRecord
from backend.schemas.common import ApiResponse
from pydantic import BaseModel

router = APIRouter(prefix="/api/knowledge", tags=["知识树"])


class TreeNode(BaseModel):
    id: int
    parent_id: int | None = None
    name: str
    level: int
    node_type: str
    interview_weight: int
    sort_order: int
    mastery_level: int = 0
    study_count: int = 0
    children: list["TreeNode"] = []


@router.get("/tree", summary="获取完整知识树")
async def get_tree(db: AsyncSession = Depends(get_db)) -> ApiResponse:
    stmt = select(KnowledgeNode).order_by(KnowledgeNode.level, KnowledgeNode.sort_order, KnowledgeNode.id)
    result = await db.execute(stmt)
    all_nodes = result.scalars().all()

    mastery_result = await db.execute(select(MasteryRecord).where(MasteryRecord.user_id == 1))
    mastery_map = {m.knowledge_point_id: m for m in mastery_result.scalars().all()}

    node_map: dict[int, TreeNode] = {}
    for n in all_nodes:
        m = mastery_map.get(n.id)
        node_map[n.id] = TreeNode(
            id=n.id, parent_id=n.parent_id, name=n.name,
            level=n.level, node_type=n.node_type,
            interview_weight=n.interview_weight, sort_order=n.sort_order,
            mastery_level=m.mastery_level if m else 0,
            study_count=m.study_count if m else 0,
        )

    roots = []
    for n in all_nodes:
        tn = node_map[n.id]
        if n.parent_id and n.parent_id in node_map:
            node_map[n.parent_id].children.append(tn)
        else:
            roots.append(tn)

    return ApiResponse.ok(data=[t.model_dump() for t in roots])
