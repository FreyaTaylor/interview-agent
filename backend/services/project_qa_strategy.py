"""
项目拷打策略 — project 侧的 QAStrategy 实现。

数据源：project_node level=3 叶子（已由 admin 解析流程预生成）
项目画像：从 ProjectUserProfile 拼装到 prompt
收尾钩子：异步触发画像抽取（fire-and-forget）
"""
from __future__ import annotations

import asyncio
import logging

from sqlalchemy.ext.asyncio import AsyncSession

from backend.models.project import Project
from backend.models.project_node import ProjectNode
from backend.models.qa import QuestionAttempt
from backend.prompts.qa_per_turn_prompt import PROJECT_PER_TURN_PROMPT
from backend.prompts.qa_final_score_prompt import PROJECT_FINAL_SCORE_PROMPT
from backend.services import project_profile as profile_svc
from backend.services.qa_engine import DEFAULT_MAX_FOLLOW_UPS, dialog_to_text

logger = logging.getLogger(__name__)


class ProjectQAStrategy:
    """项目拷打策略 — 项目导向，侧重设计深度。"""

    QUESTION_TYPE = "project"
    MAX_FOLLOW_UPS = DEFAULT_MAX_FOLLOW_UPS

    async def load_question(self, db: AsyncSession, question_id: int) -> dict:
        """question_id = project_node.id（level=3 叶子）。"""
        leaf = await db.get(ProjectNode, question_id)
        if not leaf or leaf.level != 3:
            raise ValueError("题目不存在或不是 L3 叶子")
        topic = await db.get(ProjectNode, leaf.parent_id) if leaf.parent_id else None
        topic_name = topic.name if topic else ""
        project = await _load_project_from_topic(db, topic)
        profile = (
            await profile_svc.get_or_create_profile(db, project.id)
            if project else None
        )
        return {
            "content": leaf.name,           # leaf.name = 问题内容
            "rubric_hint": [],              # project 侧不预置 rubric
            "topic_name": topic_name,
            "topic_node_id": topic.id if topic else None,
            "project": project,             # 完整 ORM，避免重复查询
            "profile": profile,
        }

    async def build_per_turn_prompt(
        self, db: AsyncSession, question_id: int,
        question_info: dict, dialog: list[dict],
        current_step: int, max_steps: int,
        prior_follow_up_types: list[str],
        allowed_follow_up_types: list[str],
    ) -> str:
        project = question_info.get("project")
        profile = question_info.get("profile")
        return PROJECT_PER_TURN_PROMPT.format(
            project_block=_render_project(project),
            profile_block=profile_svc.render_for_prompt(
                profile, current_dimension=question_info.get("topic_name", "")
            ) if profile else "（暂无画像）",
            topic_name=question_info.get("topic_name", "") or "—",
            question_content=question_info["content"],
            dialog_text=dialog_to_text(dialog),
            current_step=current_step,
            max_steps=max_steps,
            prior_follow_up_types=", ".join(prior_follow_up_types) if prior_follow_up_types else "（无）",
            allowed_follow_up_types=", ".join(allowed_follow_up_types) if allowed_follow_up_types else "（无）",
        )

    async def build_final_score_prompt(
        self, db: AsyncSession, question_id: int,
        question_info: dict, dialog: list[dict],
    ) -> str:
        project = question_info.get("project")
        return PROJECT_FINAL_SCORE_PROMPT.format(
            project_block=_render_project(project),
            topic_name=question_info.get("topic_name", "") or "—",
            question_content=question_info["content"],
            dialog_text=dialog_to_text(dialog),
        )

    async def on_finish(
        self, db: AsyncSession, attempt: QuestionAttempt, question_info: dict,
    ) -> None:
        """异步触发画像抽取 — fire-and-forget，不阻塞 API 返回。"""
        project = question_info.get("project")
        if not project:
            return
        # 摘取本轮用户回答合并送去抽取
        dialog = attempt.dialog or []
        user_texts = [m.get("content", "") for m in dialog
                      if m.get("role") == "user" and m.get("content")]
        if not user_texts:
            return
        merged_answer = "\n".join(user_texts)
        missed = [
            item.get("key_point", "")
            for item in (attempt.rubric_result or [])
            if not item.get("hit", False)
        ]
        scoring_summary = attempt.overall_summary or ""

        asyncio.create_task(profile_svc.extract_and_apply(
            project_id=project.id,
            topic=question_info.get("topic_name", "") or "",
            question=question_info["content"],
            answer=merged_answer,
            scoring_summary=scoring_summary,
            missed_key_points=missed,
        ))


# =========================================================
# 内部工具
# =========================================================
async def _load_project_from_topic(
    db: AsyncSession, topic: ProjectNode | None,
) -> Project | None:
    """从 L2 topic 反向找到所属 project（topic.parent_id 是 L1 root，再用 root → project）。"""
    if not topic or not topic.parent_id:
        return None
    from sqlalchemy import select
    root_id = topic.parent_id
    project = (await db.execute(
        select(Project).where(Project.root_node_id == root_id)
    )).scalar_one_or_none()
    return project


def _render_project(project: Project | None) -> str:
    if not project:
        return "（项目信息缺失）"
    tech = project.tech_stack
    if isinstance(tech, list):
        tech_str = ", ".join(str(t) for t in tech)
    else:
        tech_str = str(tech) if tech else "—"
    parts = [
        f"项目名：{project.name}",
        f"角色：{project.role or '—'}",
        f"技术栈：{tech_str}",
    ]
    if project.description:
        parts.append(f"描述：{project.description}")
    if project.highlights:
        parts.append(f"亮点：{project.highlights}")
    return "\n".join(parts)
