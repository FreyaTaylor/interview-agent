"""
知识树 API — 查看
"""
from fastapi import APIRouter, Depends
from sqlalchemy.ext.asyncio import AsyncSession

from backend.database import get_db
from backend.schemas.common import ApiResponse
from backend.services.knowledge_node import build_knowledge_tree

router = APIRouter(prefix="/api/knowledge", tags=["知识树"])


@router.get("/tree", summary="获取完整知识树")
async def get_tree(db: AsyncSession = Depends(get_db)) -> ApiResponse:
    """返回嵌套的三层知识树（根 → 领域 → 主题 → 知识点）。调用方：ExamPage 左侧树、Admin Outliner。"""
    return ApiResponse.ok(data=await build_knowledge_tree(db))
