"""
学习相关模型
- study_session: 学习会话
- conversation: 对话（一个知识点一次完整对话）
- conversation_message: 对话消息明细
- mastery_record: 掌握度记录
- mastery_history: 掌握度变化历史
"""
from datetime import datetime
from sqlalchemy import (
    BigInteger, SmallInteger, Float, String, Text,
    ForeignKey, DateTime, func,
)
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.orm import Mapped, mapped_column, relationship

from backend.models.base import Base, TimestampMixin


class StudySession(TimestampMixin, Base):
    """学习会话 — 一次学习活动（可能包含多个知识点的对话）"""
    __tablename__ = "study_session"

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    user_id: Mapped[int] = mapped_column(
        BigInteger, ForeignKey("user.id"), server_default="1"
    )
    # 来源类型：'text_upload'（面试复盘）| 'manual_select'（主动选题）
    source_type: Mapped[str] = mapped_column(String(20), nullable=False)
    title: Mapped[str | None] = mapped_column(String(200), nullable=True)
    started_at: Mapped[datetime] = mapped_column(
        DateTime, server_default=func.now()
    )
    ended_at: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)

    # 关系
    conversations: Mapped[list["Conversation"]] = relationship(
        "Conversation", back_populates="study_session", cascade="all, delete-orphan"
    )


class Conversation(TimestampMixin, Base):
    """对话 — 一个知识点的一次完整学习对话（动态出题→回答→打分→追问循环）"""
    __tablename__ = "conversation"

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    user_id: Mapped[int] = mapped_column(
        BigInteger, ForeignKey("user.id"), server_default="1"
    )
    study_session_id: Mapped[int] = mapped_column(
        BigInteger, ForeignKey("study_session.id"), nullable=False
    )
    knowledge_point_id: Mapped[int] = mapped_column(
        BigInteger, ForeignKey("knowledge_node.id"), nullable=False
    )
    # 当前题目内容（LLM 动态生成）
    current_question: Mapped[str | None] = mapped_column(Text, nullable=True)
    # 当前 Rubric（LLM 动态生成，JSONB）
    current_rubric: Mapped[list[dict] | None] = mapped_column(JSONB, nullable=True)
    # 当前题目的轮次（第几题）
    question_round: Mapped[int] = mapped_column(SmallInteger, server_default="1")
    # 学习小结列表（每题评分后追加，JSONB）
    learning_summaries: Mapped[list[dict] | None] = mapped_column(JSONB, nullable=True)
    # 待答题目列表（初始生成时保存，JSONB）
    pending_questions: Mapped[list[dict] | None] = mapped_column(JSONB, nullable=True)
    # 当前状态：'questioning' | 'answered' | 'finished'
    status: Mapped[str] = mapped_column(String(20), server_default="'questioning'")

    # 关系
    study_session: Mapped["StudySession"] = relationship(
        "StudySession", back_populates="conversations"
    )
    messages: Mapped[list["ConversationMessage"]] = relationship(
        "ConversationMessage", back_populates="conversation", cascade="all, delete-orphan"
    )


class ConversationMessage(TimestampMixin, Base):
    """对话消息明细 — 记录每一条消息"""
    __tablename__ = "conversation_message"

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    conversation_id: Mapped[int] = mapped_column(
        BigInteger, ForeignKey("conversation.id"), nullable=False
    )
    # 角色：'user' | 'agent'
    role: Mapped[str] = mapped_column(String(10), nullable=False)
    content: Mapped[str] = mapped_column(Text, nullable=False)
    # 消息类型：'question' | 'answer' | 'scoring' | 'follow_up' | 'summary' | 'system'
    message_type: Mapped[str] = mapped_column(String(20), nullable=False)

    # 关系
    conversation: Mapped["Conversation"] = relationship(
        "Conversation", back_populates="messages"
    )


class MasteryRecord(TimestampMixin, Base):
    """掌握度记录 — 每个用户每个知识点一条"""
    __tablename__ = "mastery_record"

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    user_id: Mapped[int] = mapped_column(
        BigInteger, ForeignKey("user.id"), server_default="1"
    )
    knowledge_point_id: Mapped[int] = mapped_column(
        BigInteger, ForeignKey("knowledge_node.id"), nullable=False
    )
    # 掌握度 0-100（最近一次 Rubric 得分）
    mastery_level: Mapped[int] = mapped_column(SmallInteger, server_default="0")
    # 遗忘曲线稳定性参数 S（越高记得越久）
    stability_s: Mapped[float] = mapped_column(Float, server_default="1.0")
    # 累计学习次数
    study_count: Mapped[int] = mapped_column(SmallInteger, server_default="0")
    last_studied_at: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime, server_default=func.now(), onupdate=func.now()
    )


class MasteryHistory(TimestampMixin, Base):
    """掌握度变化历史 — 每次学习后记录变化"""
    __tablename__ = "mastery_history"

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    knowledge_point_id: Mapped[int] = mapped_column(
        BigInteger, ForeignKey("knowledge_node.id"), nullable=False
    )
    conversation_id: Mapped[int | None] = mapped_column(
        BigInteger, ForeignKey("conversation.id"), nullable=True
    )
    score: Mapped[int] = mapped_column(SmallInteger, nullable=False)
    previous_mastery: Mapped[int | None] = mapped_column(SmallInteger, nullable=True)
    new_mastery: Mapped[int | None] = mapped_column(SmallInteger, nullable=True)
    previous_s: Mapped[float | None] = mapped_column(Float, nullable=True)
    new_s: Mapped[float | None] = mapped_column(Float, nullable=True)
