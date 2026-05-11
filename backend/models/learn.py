"""
学习内容模型
- knowledge_content: 知识点讲解内容（LLM 生成，落库缓存）
- learn_chat: 学习对话记录
"""
from datetime import datetime
from sqlalchemy import (
    BigInteger, String, Text,
    ForeignKey, DateTime, func,
)
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.orm import Mapped, mapped_column

from backend.models.base import Base, TimestampMixin


class KnowledgeContent(TimestampMixin, Base):
    """知识点讲解内容 — LLM 生成后落库，再次访问直接读取"""
    __tablename__ = "knowledge_content"

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    knowledge_point_id: Mapped[int] = mapped_column(
        BigInteger, ForeignKey("knowledge_node.id"), nullable=False, unique=True
    )
    user_id: Mapped[int] = mapped_column(BigInteger, server_default="1")
    # Markdown 格式的知识讲解长文
    content: Mapped[str] = mapped_column(Text, nullable=False)
    # 高频面试题列表 [{"question": "...", "answer": "..."}]
    questions: Mapped[list[dict] | None] = mapped_column(JSONB, nullable=True)
    # 用户对话补充的内容（追加到 content 中的记录）
    user_additions: Mapped[list[dict] | None] = mapped_column(JSONB, nullable=True)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime, server_default=func.now(), onupdate=func.now()
    )


class LearnChat(TimestampMixin, Base):
    """学习探索对话 — 在知识点内的自由对话"""
    __tablename__ = "learn_chat"

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    knowledge_point_id: Mapped[int] = mapped_column(
        BigInteger, ForeignKey("knowledge_node.id"), nullable=False
    )
    user_id: Mapped[int] = mapped_column(BigInteger, server_default="1")
    role: Mapped[str] = mapped_column(String(10), nullable=False)  # 'user' | 'assistant'
    content: Mapped[str] = mapped_column(Text, nullable=False)
    # 引用的知识文本片段（用户引用时记录）
    quoted_text: Mapped[str | None] = mapped_column(Text, nullable=True)
