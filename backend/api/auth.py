"""
认证路由 — GitHub OAuth 登录 + JWT
"""
from fastapi import APIRouter, Depends, HTTPException, Query
from fastapi.responses import RedirectResponse
from sqlalchemy.ext.asyncio import AsyncSession

from backend.config import settings
from backend.database import get_db
from backend.schemas.common import ApiResponse
from backend.services.user import (
    get_github_authorize_url, github_oauth_callback, get_current_user,
)

router = APIRouter(prefix="/api/auth", tags=["认证"])


@router.get("/github", summary="跳转 GitHub 授权")
async def github_login():
    """重定向到 GitHub OAuth 授权页，由用户点击后跳回 `/github/callback`。"""
    return RedirectResponse(get_github_authorize_url())


@router.get("/github/callback", summary="GitHub OAuth 回调")
async def github_callback(
    code: str = Query(...),
    db: AsyncSession = Depends(get_db),
):
    """接收 GitHub 返回的 code，换取 access_token → 拉取用户信息 → 上或创建 `user` 表 → 签发 JWT。最后携带 token 重定向回前端。"""
    token = await github_oauth_callback(db, code)
    if not token:
        return RedirectResponse(f"{settings.FRONTEND_URL}?error=auth_failed")
    return RedirectResponse(f"{settings.FRONTEND_URL}?token={token}")


@router.get("/me", summary="获取当前用户信息")
async def get_me(
    token: str = Query(None),
    db: AsyncSession = Depends(get_db),
):
    """根据 Query 或 LocalStorage 中的 JWT 解析出当前用户。调用方：前端 AuthContext 初始化。"""
    try:
        user_info = await get_current_user(db, token)
    except ValueError as e:
        raise HTTPException(status_code=401, detail=str(e))
    return ApiResponse.ok(user_info)
