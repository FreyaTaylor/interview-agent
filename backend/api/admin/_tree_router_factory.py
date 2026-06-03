"""管理后台树形节点路由工厂

知识树（tree_nodes）和项目树（project_nodes）的 CRUD 接口结构 90% 相同，
此工厂用一组 service callable 生成统一的 5 个端点（list / create / update /
delete / batch-sort），消除重复并保证两边行为一致（例如 PUT 用
`model_fields_set` 判断跨父移动这种修复只需在工厂里改一次）。

各 service 调用方需要给出：
  - get_all     : async (db) -> list[dict]
  - create      : async (db, parent_id, name, **extra) -> dict
  - update      : async (db, id, **fields, move_parent: bool) -> dict
  - delete      : async (db, id) -> int
  - batch_sort  : async (db, list[dict]) -> int
"""
from __future__ import annotations

from typing import Awaitable, Callable, Any

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel
from sqlalchemy.ext.asyncio import AsyncSession

from backend.database import get_db
from backend.schemas.common import ApiResponse


class BatchSortItem(BaseModel):
    id: int
    sort_order: int


class BatchSortRequest(BaseModel):
    updates: list[BatchSortItem]


def build_tree_router(
    *,
    path_segment: str,             # "tree-nodes" / "project-nodes"
    tag: str,                       # OpenAPI 分组标签
    create_request: type[BaseModel],
    update_request: type[BaseModel],
    get_all: Callable[[AsyncSession], Awaitable[list[dict]]],
    create: Callable[..., Awaitable[dict]],
    update: Callable[..., Awaitable[dict]],
    delete: Callable[[AsyncSession, int], Awaitable[int]],
    batch_sort: Callable[[AsyncSession, list[dict]], Awaitable[int]],
    extra_create_fields: tuple[str, ...] = (),
    extra_update_fields: tuple[str, ...] = (),
) -> APIRouter:
    """生成统一的树形 CRUD 路由集合。

    extra_*_fields：除 name / parent_id / sort_order 外，额外透传给 service 的字段名
    （如知识树的 interview_weight）。
    """
    router = APIRouter(prefix="/api/admin", tags=[tag])
    base = f"/{path_segment}"

    @router.get(base, summary="获取完整节点列表（编辑用）")
    async def _list(db: AsyncSession = Depends(get_db)) -> ApiResponse:
        return ApiResponse.ok(data=await get_all(db))

    @router.post(base, summary="新增节点")
    async def _create(
        req: create_request,  # type: ignore[valid-type]
        db: AsyncSession = Depends(get_db),
    ) -> ApiResponse:
        try:
            extras = {k: getattr(req, k) for k in extra_create_fields}
            result = await create(db, req.parent_id, req.name, **extras)
            return ApiResponse.ok(data=result)
        except ValueError as e:
            raise HTTPException(status_code=404, detail=str(e))

    @router.put(f"{base}/batch-sort", summary="批量更新排序")
    async def _batch_sort(
        req: BatchSortRequest,
        db: AsyncSession = Depends(get_db),
    ) -> ApiResponse:
        updates = [{"id": it.id, "sort_order": it.sort_order} for it in req.updates]
        count = await batch_sort(db, updates)
        return ApiResponse.ok(data={"updated": count})

    @router.put(base + "/{node_id}", summary="修改节点")
    async def _update(
        node_id: int,
        req: update_request,  # type: ignore[valid-type]
        db: AsyncSession = Depends(get_db),
    ) -> ApiResponse:
        try:
            # 显式传入 parent_id（包括 null）都视为跨父移动；null = 移到根
            move_parent = "parent_id" in req.model_fields_set
            kwargs: dict[str, Any] = {
                "name": req.name,
                "parent_id": req.parent_id,
                "sort_order": req.sort_order,
                "move_parent": move_parent,
            }
            for k in extra_update_fields:
                kwargs[k] = getattr(req, k)
            result = await update(db, node_id, **kwargs)
            return ApiResponse.ok(data=result)
        except ValueError as e:
            raise HTTPException(status_code=404, detail=str(e))

    @router.delete(base + "/{node_id}", summary="递归删除节点")
    async def _delete(
        node_id: int,
        db: AsyncSession = Depends(get_db),
    ) -> ApiResponse:
        try:
            deleted_id = await delete(db, node_id)
            return ApiResponse.ok(data={"deleted": deleted_id})
        except ValueError as e:
            raise HTTPException(status_code=404, detail=str(e))

    return router
