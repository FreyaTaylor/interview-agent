"""
用户模型
支持 GitHub OAuth 登录
"""
from datetime import datetime
from sqlalchemy import BigInteger, String, Text, DateTime, func
from sqlalchemy.orm import Mapped, mapped_column

from backend.models.base import Base, TimestampMixin


class User(TimestampMixin, Base):
    """用户表"""
    __tablename__ = "user"

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    username: Mapped[str] = mapped_column(String(100), unique=True, nullable=False)
    password: Mapped[str] = mapped_column(String(200), nullable=False, server_default="")
    # 角色：'admin' | 'user'
    role: Mapped[str] = mapped_column(String(20), server_default="user")
    # 用户画像原文，Agent 初始化知识树时带入 prompt
    profile_text: Mapped[str | None] = mapped_column(Text, nullable=True)
    # GitHub OAuth 字段
    github_id: Mapped[int | None] = mapped_column(BigInteger, unique=True, nullable=True)
    github_login: Mapped[str | None] = mapped_column(String(100), nullable=True)
    avatar_url: Mapped[str | None] = mapped_column(String(500), nullable=True)
