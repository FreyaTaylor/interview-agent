"""
知识树相关模型
- knowledge_node: 知识树节点（三层：一级分类/二级分类/三级叶子）
- question: 高频问题（懒生成，首次学习时由 Agent 创建）
- rubric_item: 评分关键点（每个问题 3-5 个关键点）
"""
from datetime import datetime
from sqlalchemy import (
    BigInteger, SmallInteger, Integer, String, Text, Boolean,
    ForeignKey, DateTime, func,
)
from sqlalchemy.orm import Mapped, mapped_column, relationship

from backend.models.base import Base, TimestampMixin


class KnowledgeNode(TimestampMixin, Base):
    """知识树节点 — 邻接表存储所有层级"""
    __tablename__ = "knowledge_node"

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    # 父节点，根节点为 NULL
    parent_id: Mapped[int | None] = mapped_column(
        BigInteger, ForeignKey("knowledge_node.id"), nullable=True
    )
    name: Mapped[str] = mapped_column(String(200), nullable=False)
    # 层级：1=一级分类, 2=二级分类, 3=叶子（知识点）
    level: Mapped[int] = mapped_column(SmallInteger, nullable=False)
    # 节点类型：'category' | 'leaf'
    node_type: Mapped[str] = mapped_column(String(20), nullable=False)
    # 面试权重 ★1-5，仅叶子节点有意义
    interview_weight: Mapped[int] = mapped_column(SmallInteger, server_default="3")
    # 排序
    sort_order: Mapped[int] = mapped_column(Integer, server_default="0")
    # 是否用户手动创建
    is_user_created: Mapped[bool] = mapped_column(Boolean, server_default="false")
    updated_at: Mapped[datetime] = mapped_column(
        DateTime, server_default=func.now(), onupdate=func.now()
    )

    # 关系
    children: Mapped[list["KnowledgeNode"]] = relationship(
        "KnowledgeNode", back_populates="parent", cascade="all, delete-orphan"
    )
    parent: Mapped["KnowledgeNode | None"] = relationship(
        "KnowledgeNode", back_populates="children", remote_side=[id]
    )
    questions: Mapped[list["Question"]] = relationship(
        "Question", back_populates="knowledge_point", cascade="all, delete-orphan"
    )


class Question(TimestampMixin, Base):
    """高频问题 — 懒生成，首次学习某知识点时由 Agent 创建"""
    __tablename__ = "question"

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    knowledge_point_id: Mapped[int] = mapped_column(
        BigInteger, ForeignKey("knowledge_node.id"), nullable=False
    )
    content: Mapped[str] = mapped_column(Text, nullable=False)
    # 标准答案（Agent 生成，用于参考）
    standard_answer: Mapped[str | None] = mapped_column(Text, nullable=True)
    # 难度：1=基础, 2=进阶, 3=深入
    difficulty: Mapped[int] = mapped_column(SmallInteger, server_default="1")
    # 来源：'agent' | 'pipeline' | 'user'
    source: Mapped[str] = mapped_column(String(20), server_default="agent")
    sort_order: Mapped[int] = mapped_column(Integer, server_default="0")
    updated_at: Mapped[datetime] = mapped_column(
        DateTime, server_default=func.now(), onupdate=func.now()
    )

    # 关系
    knowledge_point: Mapped["KnowledgeNode"] = relationship(
        "KnowledgeNode", back_populates="questions"
    )
    rubric_items: Mapped[list["RubricItem"]] = relationship(
        "RubricItem", back_populates="question", cascade="all, delete-orphan"
    )


class RubricItem(TimestampMixin, Base):
    """评分关键点 — 每个问题 3-5 个关键点，命中即得分"""
    __tablename__ = "rubric_item"

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    question_id: Mapped[int] = mapped_column(
        BigInteger, ForeignKey("question.id"), nullable=False
    )
    # 关键点描述（如 "SETNX+EX 原子设置"）
    key_point: Mapped[str] = mapped_column(String(500), nullable=False)
    # 该关键点的分值
    score: Mapped[int] = mapped_column(SmallInteger, nullable=False)
    sort_order: Mapped[int] = mapped_column(Integer, server_default="0")

    # 关系
    question: Mapped["Question"] = relationship(
        "Question", back_populates="rubric_items"
    )
