"""
学习答题策略 — study 侧的 QAStrategy 实现。

数据源：study_question 表（按知识点预生成）
首次进入某知识点时若无任何 study_question，自动调 LLM 生成 5 道。
"""
from __future__ import annotations

import logging

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from backend.models.knowledge import KnowledgeNode
from backend.models.qa import QuestionAttempt, StudyQuestion
from backend.prompts.qa_per_turn_prompt import STUDY_PER_TURN_PROMPT
from backend.prompts.qa_final_score_prompt import STUDY_FINAL_SCORE_PROMPT
from backend.services.qa_engine import DEFAULT_MAX_FOLLOW_UPS, dialog_to_text

logger = logging.getLogger(__name__)


class StudyQAStrategy:
    """学习答题策略 — 知识点导向。"""

    QUESTION_TYPE = "study"
    MAX_FOLLOW_UPS = DEFAULT_MAX_FOLLOW_UPS

    async def load_question(self, db: AsyncSession, question_id: int) -> dict:
        q = await db.get(StudyQuestion, question_id)
        if not q:
            raise ValueError("题目不存在")
        kp = await db.get(KnowledgeNode, q.knowledge_point_id)
        kp_path = await _build_kp_path(db, kp) if kp else ""
        return {
            "content": q.content,
            "rubric_hint": q.rubric_template or [],
            "topic_name": kp.name if kp else None,
            "kp_path": kp_path,
        }

    async def build_per_turn_prompt(
        self, db: AsyncSession, question_id: int,
        question_info: dict, dialog: list[dict],
        current_step: int, max_steps: int,
        prior_follow_up_types: list[str],
        allowed_follow_up_types: list[str],
    ) -> str:
        return STUDY_PER_TURN_PROMPT.format(
            kp_path=question_info.get("kp_path", "") or "（未关联）",
            question_content=question_info["content"],
            rubric_hint=_render_rubric_hint(question_info.get("rubric_hint")),
            dialog_text=dialog_to_text(dialog),
            current_step=current_step,
            max_steps=max_steps,
            prior_follow_up_types=_fmt_types(prior_follow_up_types),
            allowed_follow_up_types=_fmt_types(allowed_follow_up_types),
        )

    async def build_final_score_prompt(
        self, db: AsyncSession, question_id: int,
        question_info: dict, dialog: list[dict],
    ) -> str:
        return STUDY_FINAL_SCORE_PROMPT.format(
            kp_path=question_info.get("kp_path", "") or "（未关联）",
            question_content=question_info["content"],
            rubric_hint=_render_rubric_hint(question_info.get("rubric_hint")),
            dialog_text=dialog_to_text(dialog),
        )

    async def on_finish(
        self, db: AsyncSession, attempt: QuestionAttempt, question_info: dict,
    ) -> None:
        # study 侧暂无收尾副作用（mastery 是派生的，无需写表）
        return None


# =========================================================
# 内部工具
# =========================================================
async def _build_kp_path(db: AsyncSession, kp: KnowledgeNode) -> str:
    """构造 'Java → 并发 → AQS' 这种路径。"""
    parts: list[str] = [kp.name]
    cur = kp
    while cur and cur.parent_id:
        parent = await db.get(KnowledgeNode, cur.parent_id)
        if not parent:
            break
        parts.append(parent.name)
        cur = parent
    return " → ".join(reversed(parts))


def _render_rubric_hint(hint: list[dict] | None) -> str:
    if not hint:
        return "（无预置评分点，请根据题目自行识别核心要点）"
    lines = []
    for item in hint:
        kp = item.get("key_point", "")
        score = item.get("score", "")
        lines.append(f"- {kp}（{score}分）")
    return "\n".join(lines)


def _fmt_types(types: list[str]) -> str:
    """将追问类型列表渲染为 prompt 可读字符串。"""
    return ", ".join(types) if types else "（无）"
