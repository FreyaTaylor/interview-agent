"""
知识树相关模型
- knowledge_node: 知识树节点（三层：一级分类/二级分类/三级叶子）
"""
from datetime import datetime
from typing import Any
from sqlalchemy import (
    BigInteger, SmallInteger, Integer, String, Boolean,
    ForeignKey, DateTime, func,
)
from sqlalchemy.orm import Mapped, mapped_column, relationship
from pgvector.sqlalchemy import Vector

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
    # 叶子节点的 embedding 向量（用于面试问题匹配）
    embedding: Mapped[Any | None] = mapped_column(Vector(1024), nullable=True)
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
