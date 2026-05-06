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
from backend.services.tree import init_knowledge_tree, init_tree_from_image

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


class InitFromImageRequest(BaseModel):
    image_base64: str  # base64 编码的图片
    profile_text: str = ""


@router.post("/init-tree-from-image", summary="从图片初始化知识树（SSE）")
async def init_tree_from_image_api(
    req: InitFromImageRequest,
    db: AsyncSession = Depends(get_db),
):
    """
    上传知识点脑图图片初始化知识树：
    1. VL 模型识别图片中的知识点
    2. LLM 查漏补缺
    3. 写入 DB
    """
    if not req.image_base64.strip():
        raise HTTPException(status_code=400, detail="图片不能为空")

    # 保存画像
    user = await db.get(User, 1)
    if user and req.profile_text.strip():
        user.profile_text = req.profile_text.strip()
        await db.commit()

    async def event_stream():
        try:
            async for progress in init_tree_from_image(req.image_base64, req.profile_text.strip() or "Java后端开发", db):
                yield f"data: {json.dumps(progress, ensure_ascii=False)}\n\n"
        except Exception as e:
            logger.error(f"图片初始化异常: {e}")
            yield f"data: {json.dumps({'step': 'error', 'message': f'初始化异常: {e}'}, ensure_ascii=False)}\n\n"

    return StreamingResponse(
        event_stream(),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )
