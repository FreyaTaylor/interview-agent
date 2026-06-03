"""管理 API — 知识树节点 CRUD（薄壳，路由由工厂生成）"""
from pydantic import BaseModel

from backend.api.admin._tree_router_factory import build_tree_router
from backend.services.knowledge_node import (
    get_all_nodes, create_node,
    update_node, batch_update_sort, delete_node_recursive,
)


class NodeCreateRequest(BaseModel):
    parent_id: int | None = None
    name: str
    interview_weight: int = 3


class NodeUpdateRequest(BaseModel):
    name: str | None = None
    interview_weight: int | None = None
    parent_id: int | None = None
    sort_order: int | None = None


router = build_tree_router(
    path_segment="tree-nodes",
    tag="管理-节点",
    create_request=NodeCreateRequest,
    update_request=NodeUpdateRequest,
    get_all=get_all_nodes,
    create=create_node,
    update=update_node,
    delete=delete_node_recursive,
    batch_sort=batch_update_sort,
    extra_create_fields=("interview_weight",),
    extra_update_fields=("interview_weight",),
)
