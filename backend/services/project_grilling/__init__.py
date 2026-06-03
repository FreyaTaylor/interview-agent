"""项目拷打服务包（当前仅保留项目列表查询能力）。

说明：
- 主流程接口已迁移到 `qa_engine + ProjectQAStrategy`，因此这里不再 re-export 旧 session/history 模块。
- 话题列表的查询已下沉到 `api.project_grilling.history.list_dimensions`，
  使用 project_node 树作为权威源，不再走旧的 `get_dimensions`。
"""

from .project_crud import list_projects

__all__ = ["list_projects"]
