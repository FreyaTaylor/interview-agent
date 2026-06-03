"""
项目拷打相关模型
- project: 项目元数据
- project_session: 拷打会话
- project_session_message: 拷打会话消息
"""
from datetime import datetime

from sqlalchemy import (
    BigInteger, SmallInteger, Integer, String, Text,
    ForeignKey, DateTime, UniqueConstraint, func,
)
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.orm import Mapped, mapped_column, relationship

from backend.models.base import Base, TimestampMixin


class Project(TimestampMixin, Base):
    """项目元数据 — 用户的项目经历"""
    __tablename__ = "project"

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    user_id: Mapped[int] = mapped_column(BigInteger, server_default="1")
    name: Mapped[str] = mapped_column(String(200), nullable=False)
    description: Mapped[str | None] = mapped_column(Text, nullable=True)
    tech_stack: Mapped[dict | None] = mapped_column(JSONB, nullable=True)  # ["Java", "Redis", ...]
    role: Mapped[str | None] = mapped_column(String(100), nullable=True)  # "后端开发"
    highlights: Mapped[str | None] = mapped_column(Text, nullable=True)  # 核心亮点（可选）
    # 关联 project_node 树根（level=1）。删树时置空，不影响会话历史。
    root_node_id: Mapped[int | None] = mapped_column(
        BigInteger, ForeignKey("project_node.id", ondelete="SET NULL"), nullable=True,
    )

    # 关系
    sessions: Mapped[list["ProjectSession"]] = relationship(
        "ProjectSession", back_populates="project", cascade="all, delete-orphan"
    )


class ProjectSession(TimestampMixin, Base):
    """项目拷打会话 — 一次拷打练习"""
    __tablename__ = "project_session"

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    user_id: Mapped[int] = mapped_column(BigInteger, server_default="1")
    project_id: Mapped[int] = mapped_column(
        BigInteger, ForeignKey("project.id"), nullable=False
    )
    status: Mapped[str] = mapped_column(String(20), server_default="'active'")  # active / finished
    current_topic: Mapped[str | None] = mapped_column(String(200), nullable=True)
    current_question: Mapped[str | None] = mapped_column(Text, nullable=True)
    current_rubric: Mapped[dict | None] = mapped_column(JSONB, nullable=True)
    learning_summaries: Mapped[dict | None] = mapped_column(JSONB, nullable=True)  # 评分累积
    pending_questions: Mapped[dict | None] = mapped_column(JSONB, nullable=True)  # 待问题队列
    readiness_score: Mapped[int | None] = mapped_column(SmallInteger, nullable=True)  # 准备度 0-100
    follow_up_count: Mapped[int] = mapped_column(Integer, server_default="0")

    # 关系
    project: Mapped["Project"] = relationship("Project", back_populates="sessions")
    messages: Mapped[list["ProjectSessionMessage"]] = relationship(
        "ProjectSessionMessage", back_populates="session", cascade="all, delete-orphan"
    )


class ProjectSessionMessage(TimestampMixin, Base):
    """拷打会话消息 — 完整审计日志"""
    __tablename__ = "project_session_message"

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    session_id: Mapped[int] = mapped_column(
        BigInteger, ForeignKey("project_session.id"), nullable=False
    )
    # question / answer / scoring / follow_up / summary
    message_type: Mapped[str] = mapped_column(String(20), nullable=False)
    content: Mapped[str | None] = mapped_column(Text, nullable=True)
    extra: Mapped[dict | None] = mapped_column(JSONB, nullable=True)  # 评分详情等

    # 关系
    session: Mapped["ProjectSession"] = relationship("ProjectSession", back_populates="messages")


class ProjectUserProfile(TimestampMixin, Base):
    """用户在某项目上的答题画像 — 由 LLM 从历次回答中抽取，供 Agent 出题/追问参考。

    设计：
    - project_facts: 项目事实档案，分 section 存储用户答题中透露的项目信息
        {"业务与规模": [...], "技术架构": [...], "关键决策与权衡": [...]}
      4 个固定 section 由抽取 prompt 维护。
    - weak_points: 结构化薄弱点列表，每项含 topic/point/question/round
        [{"topic": "数据库", "point": "未提到回表与覆盖索引", "question": "...", "round": 3}]
    - version: 乐观锁；抽取任务异步执行，并发写时 WHERE version=? 失败则重读重算
    """
    __tablename__ = "project_user_profile"
    __table_args__ = (
        UniqueConstraint("project_id", "user_id", name="uq_project_user_profile_proj_user"),
    )

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    user_id: Mapped[int] = mapped_column(BigInteger, server_default="1", nullable=False)
    project_id: Mapped[int] = mapped_column(
        BigInteger, ForeignKey("project.id", ondelete="CASCADE"), nullable=False
    )
    project_facts: Mapped[list | None] = mapped_column(JSONB, server_default="'[]'::jsonb")
    weak_points: Mapped[list | None] = mapped_column(JSONB, server_default="'[]'::jsonb")
    version: Mapped[int] = mapped_column(Integer, server_default="0", nullable=False)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime, server_default=func.now(), onupdate=func.now()
    )


