"""
用户模型
一期仅预留，不做登录校验，所有请求视为 user_id=1
"""
from datetime import datetime
from sqlalchemy import BigInteger, String, Text, DateTime, func
from sqlalchemy.orm import Mapped, mapped_column

from backend.models.base import Base, TimestampMixin


class User(TimestampMixin, Base):
    """用户表 — 一期仅 1 条默认数据"""
    __tablename__ = "user"

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    username: Mapped[str] = mapped_column(String(100), unique=True, nullable=False)
    password: Mapped[str] = mapped_column(String(200), nullable=False)
    # 角色：'admin' | 'user'，一期不校验
    role: Mapped[str] = mapped_column(String(20), server_default="user")
    # 用户画像原文，Agent 初始化知识树时带入 prompt
    profile_text: Mapped[str | None] = mapped_column(Text, nullable=True)
