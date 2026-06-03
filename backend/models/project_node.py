"""
项目节点模型 — 项目拷打的树形结构
固定三层：项目（根） → 话题（category） → 问题（leaf）
"""
from datetime import datetime
from typing import Any

from sqlalchemy import (
    BigInteger, SmallInteger, Integer, String,
    ForeignKey, DateTime, func,
)
from sqlalchemy.orm import Mapped, mapped_column, relationship
from pgvector.sqlalchemy import Vector

from backend.models.base import Base, TimestampMixin


class ProjectNode(TimestampMixin, Base):
    """项目节点 — 邻接表存储，固定三层"""
    __tablename__ = "project_node"

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    user_id: Mapped[int] = mapped_column(BigInteger, server_default="1")
    parent_id: Mapped[int | None] = mapped_column(
        BigInteger, ForeignKey("project_node.id"), nullable=True
    )
    name: Mapped[str] = mapped_column(String(500), nullable=False)
    # 层级：1=项目, 2=话题, 3=问题
    level: Mapped[int] = mapped_column(SmallInteger, nullable=False)
    # 节点类型：'category' | 'leaf'
    node_type: Mapped[str] = mapped_column(String(20), nullable=False)
    sort_order: Mapped[int] = mapped_column(Integer, server_default="0")
    # 叶子节点的 embedding 向量（level=3 问题节点用于面试题去重匹配）
    embedding: Mapped[Any | None] = mapped_column(Vector(1024), nullable=True)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime, server_default=func.now(), onupdate=func.now()
    )

    # 关系
    children: Mapped[list["ProjectNode"]] = relationship(
        "ProjectNode", back_populates="parent", cascade="all, delete-orphan"
    )
    parent: Mapped["ProjectNode | None"] = relationship(
        "ProjectNode", back_populates="children", remote_side=[id]
    )
