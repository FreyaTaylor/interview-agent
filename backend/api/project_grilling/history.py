"""
项目拷打 API — 查询层（话题列表 / 话题下题目列表）

资源：
  GET /api/project-grilling/projects/{project_id}/dimensions
      话题列表（L2），含话题分（派生自所有 L3 题目的题目分平均）
  GET /api/project-grilling/topics/{topic_id}/questions
      话题下的题目列表（L3），含每题的题目分
"""
from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from backend.database import get_db
from backend.models.project import Project
from backend.models.project_node import ProjectNode
from backend.schemas.common import ApiResponse
from backend.services import qa_aggregate

router = APIRouter(prefix="/api/project-grilling", tags=["project-grilling"])


@router.get("/projects/{project_id}/dimensions", summary="项目话题列表（含话题分）")
async def list_dimensions(
    project_id: int, db: AsyncSession = Depends(get_db),
) -> ApiResponse:
    """返回某项目下的话题节点（L2）列表。

    每个话题携带：
      - `question_count`：该话题下 L3 题目总数
      - `attempt_count`：已作答的题目数
      - `avg_score`：由 `qa_aggregate.get_topic_score` 计算（仅对已答题平均）
    """
    project = await db.get(Project, project_id)
    if not project:
        raise HTTPException(404, "项目不存在")
    if not project.root_node_id:
        return ApiResponse.ok(data=[])

    topics = (await db.execute(
        select(ProjectNode).where(
            ProjectNode.parent_id == project.root_node_id,
            ProjectNode.level == 2,
        ).order_by(ProjectNode.sort_order, ProjectNode.id)
    )).scalars().all()

    items: list[dict] = []
    for t in topics:
        avg_score, attempt_count = await qa_aggregate.get_topic_score(db, t.id)
        # 该话题下叶子数
        leaf_count = len((await db.execute(
            select(ProjectNode.id).where(
                ProjectNode.parent_id == t.id, ProjectNode.level == 3,
            )
        )).all())
        items.append({
            "id": t.id,
            "name": t.name,
            "question_count": leaf_count,
            "attempt_count": attempt_count,
            "avg_score": avg_score,
        })
    return ApiResponse.ok(data=items)


@router.get("/topics/{topic_id}/questions", summary="话题下的题目列表（含题目分）")
async def list_topic_questions(
    topic_id: int, db: AsyncSession = Depends(get_db),
) -> ApiResponse:
    """列出某个话题下的题目（L3），含每题的最近 RECENT_N=3 作答平均分。调用方：ProjectGrillingPage 话题展开。"""
    topic = await db.get(ProjectNode, topic_id)
    if not topic or topic.level != 2:
        raise HTTPException(404, "话题不存在")

    leaves = (await db.execute(
        select(ProjectNode).where(
            ProjectNode.parent_id == topic.id, ProjectNode.level == 3,
        ).order_by(ProjectNode.sort_order, ProjectNode.id)
    )).scalars().all()

    items: list[dict] = []
    for leaf in leaves:
        score = await qa_aggregate.get_question_score(db, "project", leaf.id)
        cnt = await qa_aggregate.get_question_attempt_count(db, "project", leaf.id)
        items.append({
            "id": leaf.id,
            "content": leaf.name,
            "sort_order": leaf.sort_order,
            "score": score,
            "attempt_count": cnt,
        })
    return ApiResponse.ok(data={
        "topic_id": topic.id,
        "topic_name": topic.name,
        "questions": items,
    })
