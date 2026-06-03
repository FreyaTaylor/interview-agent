"""
QA 分数聚合工具 — 所有"分数"统一从 question_attempt 推导，不再有 mastery 表。

聚合规则（全系统一致）：
- 题目分数 = 该题最近 N 次 status='finished' 的 final_score 平均（N=3）
- 知识点 mastery = 该知识点下**所有 study_question** 的题目分数平均；**未答题按 0 分计入分母**
- 话题分 = 该话题下**所有 project_node L3** 的题目分数平均（未答按 0）
- 项目准备度 = 该项目下**所有 L2 话题** 的话题分平均（未答按 0）

这样设计：鼓励答完所有题，单题答得好不能直接拉满整个知识点的掌握度。
"""
from __future__ import annotations

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from backend.models.project_node import ProjectNode
from backend.models.qa import QuestionAttempt, StudyQuestion

# 每题统计取最近 N 次成功作答
RECENT_N = 3


# =========================================================
# 单题分数
# =========================================================
async def get_question_score(
    db: AsyncSession, question_type: str, question_id: int, user_id: int = 1,
) -> int | None:
    """返回该题最近 N 次 finished 的 final_score 平均（int），从未作答返回 None。"""
    rows = (await db.execute(
        select(QuestionAttempt.final_score).where(
            QuestionAttempt.user_id == user_id,
            QuestionAttempt.question_type == question_type,
            QuestionAttempt.question_id == question_id,
            QuestionAttempt.status == "finished",
            QuestionAttempt.final_score.isnot(None),
        ).order_by(QuestionAttempt.finished_at.desc()).limit(RECENT_N)
    )).all()
    if not rows:
        return None
    scores = [r[0] for r in rows]
    return round(sum(scores) / len(scores))


async def get_question_attempt_count(
    db: AsyncSession, question_type: str, question_id: int, user_id: int = 1,
) -> int:
    """已 finished 的作答次数（用于"已练习 N 次"展示）。"""
    rows = (await db.execute(
        select(QuestionAttempt.id).where(
            QuestionAttempt.user_id == user_id,
            QuestionAttempt.question_type == question_type,
            QuestionAttempt.question_id == question_id,
            QuestionAttempt.status == "finished",
        )
    )).all()
    return len(rows)


async def list_question_attempts(
    db: AsyncSession, question_type: str, question_id: int, user_id: int = 1,
) -> list[dict]:
    """列出该题的所有作答记录（按时间倒序），用于「历史作答」面板。

    返回的每条 dict 与 qa_engine.get_attempt 同结构，便于前端复用同一渲染组件。
    """
    rows = (await db.execute(
        select(QuestionAttempt).where(
            QuestionAttempt.user_id == user_id,
            QuestionAttempt.question_type == question_type,
            QuestionAttempt.question_id == question_id,
        ).order_by(QuestionAttempt.created_at.desc())
    )).scalars().all()
    return [
        {
            "attempt_id": a.id,
            "question_type": a.question_type,
            "question_id": a.question_id,
            "status": a.status,
            "final_score": a.final_score,
            "rubric_result": a.rubric_result or [],
            "overall_summary": a.overall_summary or "",
            "design_issues": a.design_issues or [],
            "dialog": a.dialog or [],
            "follow_up_count": a.follow_up_count,
            "created_at": a.created_at.isoformat() if a.created_at else None,
            "finished_at": a.finished_at.isoformat() if a.finished_at else None,
        }
        for a in rows
    ]


# =========================================================
# 知识点 mastery（study 侧）
# =========================================================
async def get_kp_mastery(
    db: AsyncSession, kp_id: int, user_id: int = 1,
) -> tuple[int, int]:
    """返回 (mastery_level, study_count)。
       mastery_level = 该知识点下**所有题**分数平均（**未答题按 0 分计入分母**）
       study_count   = 该知识点下所有 finished attempt 的总条数
    """
    q_ids = [qid for (qid,) in (await db.execute(
        select(StudyQuestion.id).where(StudyQuestion.knowledge_point_id == kp_id)
    )).all()]
    if not q_ids:
        return 0, 0

    score_sum = 0
    total_count = 0
    for qid in q_ids:
        score = await get_question_score(db, "study", qid, user_id)
        cnt = await get_question_attempt_count(db, "study", qid, user_id)
        total_count += cnt
        # 未答题（score=None）按 0 分计入，鼓励答完所有题
        score_sum += score or 0

    mastery = round(score_sum / len(q_ids))
    return mastery, total_count


async def get_kp_mastery_map(
    db: AsyncSession, kp_ids: list[int], user_id: int = 1,
) -> dict[int, tuple[int, int]]:
    """批量取多个知识点的 (mastery, study_count)。当前简易实现：循环单查；
    数据量大时可优化为单 SQL 聚合。"""
    out: dict[int, tuple[int, int]] = {}
    for kp_id in kp_ids:
        out[kp_id] = await get_kp_mastery(db, kp_id, user_id)
    return out


# =========================================================
# 项目话题分 / 项目准备度（project 侧）
# =========================================================
async def get_topic_score(
    db: AsyncSession, topic_node_id: int, user_id: int = 1,
) -> tuple[int | None, int]:
    """返回 (avg_score, leaf_attempt_count)。avg_score=None 表示该话题下从未答过题。"""
    leaf_ids = [lid for (lid,) in (await db.execute(
        select(ProjectNode.id).where(
            ProjectNode.parent_id == topic_node_id,
            ProjectNode.level == 3,
        )
    )).all()]
    if not leaf_ids:
        return None, 0

    score_sum = 0
    answered_any = False
    total_cnt = 0
    for lid in leaf_ids:
        score = await get_question_score(db, "project", lid, user_id)
        cnt = await get_question_attempt_count(db, "project", lid, user_id)
        total_cnt += cnt
        if score is not None:
            answered_any = True
        # 未答按 0 计入分母
        score_sum += score or 0
    if not answered_any:
        return None, total_cnt
    return round(score_sum / len(leaf_ids)), total_cnt


async def get_project_readiness(
    db: AsyncSession, project_id: int, user_id: int = 1,
) -> int | None:
    """项目准备度 = 所有 L2 话题的话题分平均。无任何答题返回 None。"""
    from backend.models.project import Project

    project = await db.get(Project, project_id)
    if not project or not project.root_node_id:
        return None

    topic_ids = [tid for (tid,) in (await db.execute(
        select(ProjectNode.id).where(
            ProjectNode.parent_id == project.root_node_id,
            ProjectNode.level == 2,
        )
    )).all()]
    if not topic_ids:
        return None

    score_sum = 0
    answered_any = False
    for tid in topic_ids:
        score, _ = await get_topic_score(db, tid, user_id)
        if score is not None:
            answered_any = True
        # 未答话题按 0 计入分母
        score_sum += score or 0
    if not answered_any:
        return None
    return round(score_sum / len(topic_ids))
