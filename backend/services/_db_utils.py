"""SQLAlchemy 通用小工具 — 仅放无副作用的纯辅助函数。"""
from __future__ import annotations

from typing import Any, TypeVar

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

T = TypeVar("T")


async def get_or_create(
    db: AsyncSession,
    model: type[T],
    *,
    filter_by: dict[str, Any],
    defaults: dict[str, Any] | None = None,
) -> T:
    """按 filter_by 查找记录，命中则返回，否则用 {**filter_by, **defaults} 新建并 flush。

    专给 "懒创建/复用某个语义唯一节点" 场景（如「未命名知识点」「未命名项目」根）。
    调用方负责 commit。
    """
    obj = (await db.execute(select(model).filter_by(**filter_by))).scalar_one_or_none()
    if obj is not None:
        return obj
    kwargs = {**filter_by, **(defaults or {})}
    obj = model(**kwargs)
    db.add(obj)
    await db.flush()
    return obj
