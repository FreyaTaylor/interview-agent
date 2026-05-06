"""
管理 API — 用户画像 + 知识树初始化
"""
import json
import logging
from fastapi import APIRouter, Depends, HTTPException
from fastapi.responses import StreamingResponse
from pydantic import BaseModel
from sqlalchemy import select, func
from sqlalchemy.ext.asyncio import AsyncSession

from backend.database import get_db
from backend.models.user import User
from backend.models.knowledge import KnowledgeNode
from backend.schemas.common import ApiResponse
from backend.services.tree import init_knowledge_tree

logger = logging.getLogger(__name__)
router = APIRouter(prefix="/api/admin", tags=["管理"])


class ProfileRequest(BaseModel):
    profile_text: str


class InitRequest(BaseModel):
    profile_text: str


@router.get("/profile", summary="获取用户画像")
async def get_profile(db: AsyncSession = Depends(get_db)) -> ApiResponse:
    user = await db.get(User, 1)
    return ApiResponse.ok(data={
        "profile_text": user.profile_text if user else "",
    })


@router.put("/profile", summary="更新用户画像")
async def update_profile(
    req: ProfileRequest,
    db: AsyncSession = Depends(get_db),
) -> ApiResponse:
    user = await db.get(User, 1)
    if not user:
        user = User(id=1, username="admin", password="admin", role="admin")
        db.add(user)
    user.profile_text = req.profile_text.strip()
    await db.commit()
    return ApiResponse.ok(data={"profile_text": user.profile_text})


@router.get("/tree-stats", summary="知识树统计")
async def tree_stats(db: AsyncSession = Depends(get_db)) -> ApiResponse:
    """返回知识树的基本统计信息"""
    leaf_count = (await db.execute(
        select(func.count()).where(KnowledgeNode.node_type == "leaf")
    )).scalar() or 0
    cat1_count = (await db.execute(
        select(func.count()).where(KnowledgeNode.level == 1)
    )).scalar() or 0
    return ApiResponse.ok(data={
        "leaf_count": leaf_count,
        "category_count": cat1_count,
        "initialized": leaf_count > 0,
    })


@router.post("/init-tree", summary="初始化知识树（SSE 流式进度）")
async def init_tree(
    req: InitRequest,
    db: AsyncSession = Depends(get_db),
):
    """
    根据用户画像初始化知识树。
    使用 Server-Sent Events 实时推送进度。
    会清空现有知识树！
    """
    if not req.profile_text.strip():
        raise HTTPException(status_code=400, detail="用户画像不能为空")

    # 同时保存画像
    user = await db.get(User, 1)
    if not user:
        user = User(id=1, username="admin", password="admin", role="admin")
        db.add(user)
    user.profile_text = req.profile_text.strip()
    await db.commit()

    async def event_stream():
        try:
            async for progress in init_knowledge_tree(req.profile_text.strip(), db):
                yield f"data: {json.dumps(progress, ensure_ascii=False)}\n\n"
        except Exception as e:
            logger.error(f"知识树初始化异常: {e}")
            yield f"data: {json.dumps({'step': 'error', 'message': f'初始化异常: {e}'}, ensure_ascii=False)}\n\n"

    return StreamingResponse(
        event_stream(),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )


# ---- 知识树编辑 API ----

class NodeCreateRequest(BaseModel):
    parent_id: int | None = None
    name: str
    interview_weight: int = 3


class NodeUpdateRequest(BaseModel):
    name: str | None = None
    interview_weight: int | None = None
    parent_id: int | None = None


@router.get("/tree-nodes", summary="获取完整知识树（编辑用）")
async def get_tree_nodes(db: AsyncSession = Depends(get_db)) -> ApiResponse:
    stmt = select(KnowledgeNode).order_by(KnowledgeNode.level, KnowledgeNode.sort_order, KnowledgeNode.id)
    result = await db.execute(stmt)
    nodes = result.scalars().all()
    return ApiResponse.ok(data=[{
        "id": n.id, "parent_id": n.parent_id, "name": n.name,
        "level": n.level, "node_type": n.node_type,
        "interview_weight": n.interview_weight,
    } for n in nodes])


@router.post("/tree-nodes", summary="新增知识点节点")
async def create_tree_node(
    req: NodeCreateRequest,
    db: AsyncSession = Depends(get_db),
) -> ApiResponse:
    if not req.name.strip():
        raise HTTPException(status_code=400, detail="名称不能为空")

    # 确定层级
    if req.parent_id:
        parent = await db.get(KnowledgeNode, req.parent_id)
        if not parent:
            raise HTTPException(status_code=404, detail="父节点不存在")
        level = parent.level + 1
        node_type = "leaf" if level >= 3 else "category"
    else:
        level = 1
        node_type = "category"

    node = KnowledgeNode(
        parent_id=req.parent_id,
        name=req.name.strip(),
        level=level,
        node_type=node_type,
        interview_weight=req.interview_weight,
    )
    db.add(node)
    await db.commit()
    return ApiResponse.ok(data={"id": node.id, "name": node.name, "level": level})


@router.put("/tree-nodes/{node_id}", summary="修改知识点节点")
async def update_tree_node(
    node_id: int,
    req: NodeUpdateRequest,
    db: AsyncSession = Depends(get_db),
) -> ApiResponse:
    node = await db.get(KnowledgeNode, node_id)
    if not node:
        raise HTTPException(status_code=404, detail="节点不存在")
    if req.name is not None:
        node.name = req.name.strip()
    if req.interview_weight is not None:
        node.interview_weight = req.interview_weight
    if req.parent_id is not None:
        node.parent_id = req.parent_id
    await db.commit()
    return ApiResponse.ok(data={"id": node.id, "name": node.name})


@router.delete("/tree-nodes/{node_id}", summary="删除知识点节点（含子节点）")
async def delete_tree_node(
    node_id: int,
    db: AsyncSession = Depends(get_db),
) -> ApiResponse:
    node = await db.get(KnowledgeNode, node_id)
    if not node:
        raise HTTPException(status_code=404, detail="节点不存在")

    # 递归删除子节点
    async def delete_children(parent_id: int):
        children = await db.execute(
            select(KnowledgeNode).where(KnowledgeNode.parent_id == parent_id)
        )
        for child in children.scalars().all():
            await delete_children(child.id)
            await db.delete(child)

    await delete_children(node.id)
    await db.delete(node)
    await db.commit()
    return ApiResponse.ok(data={"deleted": node_id})
