"""
认证服务 — GitHub OAuth + JWT

职责：
  1. JWT 签发与验证
  2. GitHub OAuth 授权链接生成、code 换 token、获取 GitHub 用户信息
  3. GitHub 用户落库（查找或创建）
  4. 根据 token 获取当前登录用户信息（供 /me 接口使用）
  5. GitHub OAuth 回调完整流程编排（供 /callback 接口使用）
"""
import logging
from datetime import datetime, timedelta

import httpx
import jwt
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from backend.config import settings
from backend.models.user import User

logger = logging.getLogger(__name__)

JWT_ALGORITHM = "HS256"
JWT_EXPIRE_DAYS = 30


# ========== JWT 签发 / 验证 ==========

def create_jwt(user_id: int, username: str) -> str:
    """
    签发 JWT token。
    payload 包含 user_id、username、exp（30 天后过期）。
    """
    payload = {
        "user_id": user_id,
        "username": username,
        "exp": datetime.utcnow() + timedelta(days=JWT_EXPIRE_DAYS),
    }
    return jwt.encode(payload, settings.JWT_SECRET, algorithm=JWT_ALGORITHM)


def decode_jwt(token: str) -> dict | None:
    """
    解析并验证 JWT token。
    过期或签名无效时返回 None，不抛异常。
    """
    try:
        return jwt.decode(token, settings.JWT_SECRET, algorithms=[JWT_ALGORITHM])
    except (jwt.ExpiredSignatureError, jwt.InvalidTokenError):
        return None


# ========== GitHub OAuth ==========

def get_github_authorize_url() -> str:
    """拼装 GitHub OAuth 授权页 URL，scope 只要求 read:user。"""
    return (
        f"https://github.com/login/oauth/authorize"
        f"?client_id={settings.GITHUB_CLIENT_ID}"
        f"&scope=read:user"
    )


async def _exchange_github_code(code: str) -> dict | None:
    """
    内部方法：用一次性 code 向 GitHub 换取 access_token，
    再用 access_token 调 /user 接口拿用户资料。
    失败返回 None。
    """
    async with httpx.AsyncClient() as client:
        # 第一步：code → access_token
        token_resp = await client.post(
            "https://github.com/login/oauth/access_token",
            json={
                "client_id": settings.GITHUB_CLIENT_ID,
                "client_secret": settings.GITHUB_CLIENT_SECRET,
                "code": code,
            },
            headers={"Accept": "application/json"},
        )
        token_data = token_resp.json()
        access_token = token_data.get("access_token")
        if not access_token:
            logger.error(f"GitHub token 交换失败: {token_data}")
            return None

        # 第二步：access_token → 用户资料
        user_resp = await client.get(
            "https://api.github.com/user",
            headers={"Authorization": f"Bearer {access_token}"},
        )
        return user_resp.json()


async def _find_or_create_github_user(
    db: AsyncSession, github_id: int, github_login: str, avatar_url: str,
) -> User:
    """
    内部方法：按 github_id 查表。
    - 已存在 → 更新 login 和头像（用户可能改过 GitHub 名字）
    - 不存在 → 新建用户
    """
    result = await db.execute(
        select(User).where(User.github_id == github_id)
    )
    user = result.scalar_one_or_none()

    if user:
        user.github_login = github_login
        user.avatar_url = avatar_url
    else:
        user = User(
            username=github_login,
            github_id=github_id,
            github_login=github_login,
            avatar_url=avatar_url,
        )
        db.add(user)

    await db.commit()
    await db.refresh(user)
    return user


# ========== 对外业务方法（供 API 层直接调用） ==========

async def github_oauth_callback(db: AsyncSession, code: str) -> str | None:
    """
    GitHub OAuth 回调完整流程：
      1. 用 code 换 GitHub 用户信息
      2. 落库（查找或创建）
      3. 签发 JWT
    成功返回 JWT token 字符串，失败返回 None。
    """
    gh_user = await _exchange_github_code(code)
    if not gh_user or not gh_user.get("id"):
        return None

    user = await _find_or_create_github_user(
        db,
        github_id=gh_user["id"],
        github_login=gh_user.get("login", ""),
        avatar_url=gh_user.get("avatar_url", ""),
    )
    return create_jwt(user.id, user.username)


async def get_current_user(db: AsyncSession, token: str | None) -> dict:
    """
    根据 JWT token 获取当前用户信息。
    返回包含 id、username、github_login、avatar_url、profile_text 的字典。
    token 无效或用户不存在时抛 ValueError。
    """
    if not token:
        raise ValueError("未登录")
    payload = decode_jwt(token)
    if not payload:
        raise ValueError("无效 token")
    user = await db.get(User, payload["user_id"])
    if not user:
        raise ValueError("用户不存在")
    return {
        "id": user.id,
        "username": user.username,
        "github_login": user.github_login,
        "avatar_url": user.avatar_url,
        "profile_text": user.profile_text or "",
    }
