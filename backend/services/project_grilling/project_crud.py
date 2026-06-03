"""
项目拷打子模块 — 项目查询 + 考核维度

职责（"创建"由 admin 解析独占，本模块不再 CRUD）：
- 项目列表（含真题数 + 最近准备度统计）
- 考核维度列表（以 project_node 树为权威源，回填历史分数）
"""
from __future__ import annotations

from sqlalchemy import select, func
from sqlalchemy.ext.asyncio import AsyncSession

from backend.models.project import Project, ProjectSession
from backend.models.project_node import ProjectNode

# 历史兼容常量：项目介绍是伪维度，不在 project_node 树里。
PROJECT_INTRO_DIMENSION = "项目介绍"


# =========================================================
# 项目查询
# =========================================================

async def list_projects(db: AsyncSession) -> list[dict]:
    """获取所有项目列表，附带"真题数"和"最近准备度分数"。

    Step 1: 拉取当前用户全部项目（按创建时间倒序）
    Step 2: 通过 root_node_id 关联 project_node 树，统计 level=3 真题叶子数
    Step 3: 取最近一个 finished session 的 readiness_score 作为"准备度"
    """
    # ===== Step 1: 拉取项目列表 =====
    projects = (await db.execute(
        select(Project).where(Project.user_id == 1).order_by(Project.created_at.desc())
    )).scalars().all()

    data: list[dict] = []
    for p in projects:
        # ===== Step 2: FK 关联统计真题数 =====
        real_q_count = 0
        if p.root_node_id:
            topic_ids = [tid for (tid,) in (await db.execute(
                select(ProjectNode.id).where(
                    ProjectNode.parent_id == p.root_node_id,
                    ProjectNode.level == 2,
                )
            )).all()]
            if topic_ids:
                real_q_count = (await db.execute(
                    select(func.count(ProjectNode.id)).where(
                        ProjectNode.parent_id.in_(topic_ids),
                        ProjectNode.level == 3,
                    )
                )).scalar_one() or 0

        # ===== Step 3: 最近一次 finished 会话准备度 =====
        last_session = (await db.execute(
            select(ProjectSession).where(
                ProjectSession.project_id == p.id,
                ProjectSession.status == "finished",
            ).order_by(ProjectSession.created_at.desc()).limit(1)
        )).scalar_one_or_none()

        data.append({
            "id": p.id,
            "name": p.name,
            "description": p.description,
            "tech_stack": p.tech_stack or [],
            "role": p.role,
            "highlights": p.highlights,
            "real_question_count": real_q_count,
            "readiness_score": last_session.readiness_score if last_session else None,
            "created_at": p.created_at.isoformat() if p.created_at else None,
        })
    return data


# =========================================================
# 考核维度
# =========================================================

async def get_dimensions(db: AsyncSession, project_id: int) -> list[dict]:
    """返回项目的考核维度列表，含历史答题统计。

    数据源策略：
    - 维度名以 project_node 树 level=2 为**唯一权威源**（防止历史脏数据混入）
    - 历史分数从 learning_summaries 按 topic 名精确匹配回填，不匹配的话题忽略
    - 「项目介绍」是特殊伪维度，强制置顶插入

    Step 1: 取项目实体
    Step 2: 从 project_node 树拉 level=2 话题作为权威维度集
    Step 3: 遍历历史 session.learning_summaries 回填分数
    Step 4: 计算"项目介绍"的历史分数（单独统计因为它不在树里）
    Step 5: 组装输出，「项目介绍」置顶
    """
    # ===== Step 1: 项目实体 =====
    project = (await db.execute(
        select(Project).where(Project.id == project_id)
    )).scalar_one_or_none()
    if not project:
        return []

    # ===== Step 2: 权威维度集（通过 root_node_id FK 关联）=====
    dim_stats: dict[str, dict] = {}
    if project.root_node_id:
        topics = (await db.execute(
            select(ProjectNode).where(
                ProjectNode.parent_id == project.root_node_id,
                ProjectNode.level == 2,
            ).order_by(ProjectNode.sort_order, ProjectNode.id)
        )).scalars().all()
        for t in topics:
            topic = (t.name or "").strip()
            if topic:
                dim_stats[topic] = {"count": 0, "total_score": 0, "last_score": None}

    # ===== Step 3: 历史分数回填 =====
    sessions = (await db.execute(
        select(ProjectSession).where(ProjectSession.project_id == project_id)
    )).scalars().all()
    for s in sessions:
        for summary in (s.learning_summaries or []):
            if not isinstance(summary, dict):
                continue
            topic = summary.get("topic", "").strip()
            if topic not in dim_stats:
                continue  # 树外脏话题忽略，保持权威源干净
            dim_stats[topic]["count"] += 1
            score = summary.get("score", 0)
            dim_stats[topic]["total_score"] += score
            dim_stats[topic]["last_score"] = score

    out = [{
        "name": topic,
        "count": stats["count"],
        "avg_score": round(stats["total_score"] / stats["count"]) if stats["count"] > 0 else None,
        "last_score": stats["last_score"],
    } for topic, stats in dim_stats.items()]

    # ===== Step 4: 项目介绍单独统计 =====
    intro_stats = {"count": 0, "total_score": 0, "last_score": None}
    for s in sessions:
        for summary in (s.learning_summaries or []):
            if isinstance(summary, dict) and summary.get("topic") == PROJECT_INTRO_DIMENSION:
                intro_stats["count"] += 1
                score = summary.get("score", 0)
                intro_stats["total_score"] += score
                intro_stats["last_score"] = score

    # ===== Step 5: 置顶项目介绍 =====
    out.insert(0, {
        "name": PROJECT_INTRO_DIMENSION,
        "count": intro_stats["count"],
        "avg_score": (
            round(intro_stats["total_score"] / intro_stats["count"])
            if intro_stats["count"] > 0 else None
        ),
        "last_score": intro_stats["last_score"],
    })
    return out
