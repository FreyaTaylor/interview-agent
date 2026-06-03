"""
用户画像服务
"""
from sqlalchemy.ext.asyncio import AsyncSession

from backend.models.user import User


async def get_profile(db: AsyncSession, user_id: int = 1) -> str:
    """获取用户画像文本"""
    user = await db.get(User, user_id)
    return user.profile_text if user else ""


async def update_profile(db: AsyncSession, profile_text: str, user_id: int = 1) -> str:
    """更新用户画像"""
    user = await db.get(User, user_id)
    if not user:
        user = User(id=user_id, username="admin", role="admin")
        db.add(user)
    user.profile_text = profile_text.strip()
    await db.commit()
    return user.profile_text
