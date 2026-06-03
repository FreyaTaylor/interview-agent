"""管理 API — 项目节点 CRUD（薄壳：5 个通用路由由工厂生成 + 1 个项目特有的 from-text）"""
from fastapi import Depends
from pydantic import BaseModel
from sqlalchemy.ext.asyncio import AsyncSession

from backend.api.admin._tree_router_factory import build_tree_router
from backend.database import get_db
from backend.schemas.common import ApiResponse
from backend.services.project_node import (
    get_all_nodes, create_node,
    update_node, batch_update_sort, delete_node_recursive,
    create_project_from_text,
)


class ProjectNodeCreateRequest(BaseModel):
    parent_id: int | None = None
    name: str


class ProjectNodeUpdateRequest(BaseModel):
    name: str | None = None
    parent_id: int | None = None
    sort_order: int | None = None


class ProjectFromTextRequest(BaseModel):
    text: str


router = build_tree_router(
    path_segment="project-nodes",
    tag="管理-项目节点",
    create_request=ProjectNodeCreateRequest,
    update_request=ProjectNodeUpdateRequest,
    get_all=get_all_nodes,
    create=create_node,
    update=update_node,
    delete=delete_node_recursive,
    batch_sort=batch_update_sort,
)


@router.post("/project-nodes/from-text", summary="从文本描述创建项目（LLM解析+去重）")
async def create_project_from_text_api(
    req: ProjectFromTextRequest,
    db: AsyncSession = Depends(get_db),
) -> ApiResponse:
    """用户贴一大段项目描述，由 LLM 抽取：项目名 / 话题 / 题目三层结构，并按名称去重；同时初始化项目画像。"""
    try:
        result = await create_project_from_text(req.text, db)
        return ApiResponse.ok(data=result)
    except ValueError as e:
        return ApiResponse.error(40901, str(e))
    except RuntimeError as e:
        return ApiResponse.error(50001, str(e))
