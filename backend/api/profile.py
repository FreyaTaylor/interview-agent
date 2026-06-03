"""
用户画像路由
"""
from fastapi import APIRouter, Depends
from pydantic import BaseModel
from sqlalchemy.ext.asyncio import AsyncSession

from backend.database import get_db
from backend.schemas.common import ApiResponse
from backend.services.profile import get_profile as svc_get_profile
from backend.services.profile import update_profile as svc_update_profile

router = APIRouter(prefix="/api/user", tags=["用户"])


class ProfileRequest(BaseModel):
    profile_text: str


@router.get("/profile", summary="获取用户画像")
async def get_profile(db: AsyncSession = Depends(get_db)) -> ApiResponse:
    """返回当前用户的纯文本画像（技术栈 / 项目背景 / 偏好等）。

    - 表：`user.profile`（`User` 列）
    - 调用方：ProfilePage 打开、LearnPage / QA 为 LLM 提供上下文
    """
    text = await svc_get_profile(db)
    return ApiResponse.ok(data={"profile_text": text})


@router.put("/profile", summary="更新用户画像")
async def update_profile(
    req: ProfileRequest,
    db: AsyncSession = Depends(get_db),
) -> ApiResponse:
    """以整段文本覆写用户画像。调用方：ProfilePage "保存" 按钮。"""
    text = await svc_update_profile(db, req.profile_text)
    return ApiResponse.ok(data={"profile_text": text})
