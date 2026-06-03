"""
QA 引擎 — "按题作答 + 整轮综合打分" 的统一执行框架。

核心抽象：
- `QAStrategy`：策略接口，描述如何加载题目、构造 prompt、收尾处理
- `start_attempt` / `process_turn` / `finish_attempt` / `get_attempt`：通用流程

设计原则：
- API 薄路由层只负责入参校验和响应包装，所有业务逻辑在此
- 两个领域（study / project）共享流程，差异封装在 strategy 里
- 一道题 = 主问 + N 个追问 + 最后一次综合打分
- 上限：MAX_FOLLOW_UPS（默认 4，即主问 + 4 追问 = 5 轮）
- 自然结束（LLM 判定无需追问）或用户手动 finish 都走同一收尾流程

dialog 结构（落 question_attempt.dialog）：
  [
    {"role": "agent", "type": "question",   "content": "..."},
    {"role": "user",  "type": "answer",     "content": "..."},
    {"role": "agent", "type": "feedback",   "content": "范例回答..", "covered": true},
    {"role": "agent", "type": "follow_up",  "content": "..."},
    {"role": "user",  "type": "answer",     "content": "..."},
    ...
  ]
"""
from __future__ import annotations

import logging
from datetime import datetime
from typing import Any, Protocol

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm.attributes import flag_modified

from backend.models.qa import QuestionAttempt
from backend.services.llm import get_llm, parse_llm_json

logger = logging.getLogger(__name__)

# 一道题最多追问几次（不含主问）
DEFAULT_MAX_FOLLOW_UPS = 4


class QAStrategy(Protocol):
    """策略接口 — study / project 各实现一份。"""

    QUESTION_TYPE: str               # 'study' | 'project'
    MAX_FOLLOW_UPS: int

    async def load_question(self, db: AsyncSession, question_id: int) -> dict:
        """加载题目核心信息：{content, rubric_hint, topic_name, extra_context}。"""
        ...

    async def build_per_turn_prompt(
        self, db: AsyncSession, question_id: int,
        question_info: dict, dialog: list[dict],
        current_step: int, max_steps: int,
        prior_follow_up_types: list[str],
        allowed_follow_up_types: list[str],
    ) -> str:
        ...

    async def build_final_score_prompt(
        self, db: AsyncSession, question_id: int,
        question_info: dict, dialog: list[dict],
    ) -> str:
        ...

    async def on_finish(
        self, db: AsyncSession, attempt: QuestionAttempt, question_info: dict,
    ) -> None:
        """收尾钩子（如：项目侧异步抽取画像）。可空实现。"""
        ...


# =========================================================
# Step 1：开始作答
# =========================================================
async def start_attempt(
    db: AsyncSession, strategy: QAStrategy, question_id: int, user_id: int = 1,
) -> dict:
    """创建一条新的 question_attempt，落主问题到 dialog[0]，返回起手信息。

    业务约束：一道题在 user + question_type 维度下，**最多一条 in_progress**。
    若已存在未结束的 attempt，直接拒绝创建（前端应改走 load 流程继续作答，
    或先调 finish 收尾）。
    """
    # ===== 守卫：禁止重复创建 in_progress =====
    existing = (await db.execute(
        select(QuestionAttempt).where(
            QuestionAttempt.user_id == user_id,
            QuestionAttempt.question_type == strategy.QUESTION_TYPE,
            QuestionAttempt.question_id == question_id,
            QuestionAttempt.status == "in_progress",
        )
    )).scalar_one_or_none()
    if existing:
        raise ValueError(
            f"该题已有进行中的作答（attempt_id={existing.id}），"
            f"请先「结束并打分」或继续作答，不能重复开启"
        )

    question_info = await strategy.load_question(db, question_id)

    attempt = QuestionAttempt(
        user_id=user_id,
        question_type=strategy.QUESTION_TYPE,
        question_id=question_id,
        status="in_progress",
        dialog=[{
            "role": "agent",
            "type": "question",
            "content": question_info["content"],
        }],
        follow_up_count=0,
    )
    db.add(attempt)
    await db.commit()
    await db.refresh(attempt)

    return {
        "attempt_id": attempt.id,
        "question_id": question_id,
        "question_type": strategy.QUESTION_TYPE,
        "topic_name": question_info.get("topic_name"),
        "question_content": question_info["content"],
        "current_step": 0,
        "max_steps": strategy.MAX_FOLLOW_UPS,
        "dialog": attempt.dialog,
    }


# =========================================================
# Step 2：每轮作答 — 用户回答 → LLM 判定 → 范例 + 可选追问
# =========================================================
async def process_turn(
    db: AsyncSession, strategy: QAStrategy, attempt_id: int, answer: str,
) -> dict:
    """处理用户一轮回答：

    返回：
      {
        covered: bool,
        recommended_answer: list[str] | str,  # 新版 prompt 返回分点数组；旧数据仍可能是 str
        follow_up_question: str | null,  # null 表示本题已结束（外层应调 finish）
        current_step: int,                # 当前已完成的追问轮数
        max_steps: int,
        dialog: list[dict],
      }
    """
    attempt = await db.get(QuestionAttempt, attempt_id)
    if not attempt:
        raise ValueError("attempt 不存在")
    if attempt.status != "in_progress":
        raise ValueError("attempt 已结束，不可继续作答")

    answer = (answer or "").strip()
    if not answer:
        raise ValueError("回答内容为空")

    question_info = await strategy.load_question(db, attempt.question_id)

    # 落用户回答
    dialog: list[dict] = list(attempt.dialog or [])
    dialog.append({"role": "user", "type": "answer", "content": answer})

    # ===== 状态机：根据历史追问类型计算允许的下一种追问类型 =====
    # 规则：horizontal 一次封顶（"漏点提醒"只问一次），deep_dive 可重复（mastery=high 时持续深挖）
    prior_follow_up_types: list[str] = [
        str(m.get("follow_up_type")) for m in dialog
        if m.get("type") == "follow_up" and m.get("follow_up_type")
    ]
    has_horizontal = "horizontal" in prior_follow_up_types
    allowed_follow_up_types: list[str] = []
    if not has_horizontal:
        allowed_follow_up_types.append("horizontal")
    # deep_dive 不限次数（受 MAX_FOLLOW_UPS 总轮数兜底）
    allowed_follow_up_types.append("deep_dive")

    current_step = attempt.follow_up_count  # 已完成的追问轮数
    max_steps = strategy.MAX_FOLLOW_UPS
    prompt = await strategy.build_per_turn_prompt(
        db, attempt.question_id, question_info, dialog,
        current_step=current_step, max_steps=max_steps,
        prior_follow_up_types=prior_follow_up_types,
        allowed_follow_up_types=allowed_follow_up_types,
    )

    llm = get_llm(temperature=0.2)
    try:
        resp = await llm.ainvoke(prompt)
        parsed = parse_llm_json(resp.content) or {}
    except Exception as e:
        logger.exception("per-turn LLM 调用失败")
        raise RuntimeError(f"LLM 调用失败: {e}") from e

    covered = bool(parsed.get("covered", False))
    mastery_raw = (parsed.get("mastery") or "").strip().lower()
    mastery = mastery_raw if mastery_raw in ("high", "mid", "low") else "mid"

    ra_raw = parsed.get("recommended_answer")
    if isinstance(ra_raw, list):
        recommended_answer: list[str] | str = [
            str(x).strip() for x in ra_raw if str(x).strip()
        ]
    else:
        recommended_answer = (ra_raw or "").strip() if isinstance(ra_raw, str) else ""

    follow_up_question = parsed.get("follow_up_question")
    if isinstance(follow_up_question, str):
        follow_up_question = follow_up_question.strip() or None
    follow_up_type_raw = (parsed.get("follow_up_type") or "").strip().lower()
    follow_up_type: str | None = (
        follow_up_type_raw if follow_up_type_raw in ("horizontal", "deep_dive") else None
    )

    # ===== Python 侧强制状态机校正 =====
    # 1) 类型必须在允许列表里
    if follow_up_type and follow_up_type not in allowed_follow_up_types:
        follow_up_type = None
        follow_up_question = None
    # 2) deep_dive 只在 mastery=high 时允许
    if follow_up_type == "deep_dive" and mastery != "high":
        follow_up_type = None
        follow_up_question = None
    # 3) horizontal 只在 covered=false 时有意义
    if follow_up_type == "horizontal" and covered:
        follow_up_type = None
        follow_up_question = None
    # 4) 缺一不可
    if not follow_up_type or not follow_up_question:
        follow_up_type = None
        follow_up_question = None
    # 5) 兜底：超过 max_steps 强制结束
    if current_step >= max_steps:
        follow_up_type = None
        follow_up_question = None

    # 落范例反馈
    dialog.append({
        "role": "agent",
        "type": "feedback",
        "content": recommended_answer,
        "covered": covered,
        "mastery": mastery,
    })

    # 追问 or 结束
    if follow_up_question:
        dialog.append({
            "role": "agent",
            "type": "follow_up",
            "follow_up_type": follow_up_type,
            "content": follow_up_question,
        })
        attempt.follow_up_count = current_step + 1

    attempt.dialog = dialog
    flag_modified(attempt, "dialog")
    await db.commit()

    return {
        "attempt_id": attempt.id,
        "covered": covered,
        "mastery": mastery,
        "recommended_answer": recommended_answer,
        "follow_up_type": follow_up_type,
        "follow_up_question": follow_up_question,
        "current_step": attempt.follow_up_count,
        "max_steps": max_steps,
        "dialog": dialog,
    }


# =========================================================
# Step 3：收尾 — 综合评分
# =========================================================
async def finish_attempt(
    db: AsyncSession, strategy: QAStrategy, attempt_id: int,
) -> dict:
    """对完整 dialog 做综合评分；写入 final_score / rubric_result / overall_summary。

    幂等：已 finished 的 attempt 直接返回已存的结果。
    """
    attempt = await db.get(QuestionAttempt, attempt_id)
    if not attempt:
        raise ValueError("attempt 不存在")
    if attempt.status == "finished":
        return _serialize_finished(attempt)

    question_info = await strategy.load_question(db, attempt.question_id)
    dialog = list(attempt.dialog or [])
    if not any(m for m in dialog if m.get("role") == "user"):
        # 没有任何回答 → 标记 abandoned，不打分
        attempt.status = "abandoned"
        attempt.finished_at = datetime.utcnow()
        await db.commit()
        return {
            "attempt_id": attempt.id,
            "status": "abandoned",
            "final_score": None,
            "rubric_result": [],
            "overall_summary": "未作答",
            "design_issues": [],
            "extension_qa": [],
            "dialog": dialog,
        }

    prompt = await strategy.build_final_score_prompt(
        db, attempt.question_id, question_info, dialog,
    )

    llm = get_llm(temperature=0.1, max_tokens=2048)
    try:
        resp = await llm.ainvoke(prompt)
        parsed = parse_llm_json(resp.content) or {}
    except Exception as e:
        logger.exception("final-score LLM 调用失败")
        raise RuntimeError(f"LLM 评分失败: {e}") from e

    final_score = _safe_int(parsed.get("final_score"), default=0, lo=0, hi=100)
    rubric_result = parsed.get("rubric_result") or []
    overall_summary = (parsed.get("overall_summary") or "").strip()
    design_issues = parsed.get("design_issues") or None  # 仅 project 输出
    # 延伸 Q&A：规范化为 [{q, a}, ...]
    ext_raw = parsed.get("extension_qa") or []
    extension_qa: list[dict] = []
    if isinstance(ext_raw, list):
        for item in ext_raw:
            if not isinstance(item, dict):
                continue
            q = str(item.get("q") or "").strip()
            a = str(item.get("a") or "").strip()
            if q and a:
                extension_qa.append({"q": q, "a": a})

    attempt.final_score = final_score
    attempt.rubric_result = rubric_result if isinstance(rubric_result, list) else []
    attempt.overall_summary = overall_summary
    attempt.design_issues = design_issues if isinstance(design_issues, list) else None
    attempt.extension_qa = extension_qa or None
    attempt.status = "finished"
    attempt.finished_at = datetime.utcnow()
    flag_modified(attempt, "rubric_result")
    if attempt.design_issues is not None:
        flag_modified(attempt, "design_issues")
    if attempt.extension_qa is not None:
        flag_modified(attempt, "extension_qa")
    await db.commit()

    # 收尾钩子（如项目画像抽取）
    try:
        await strategy.on_finish(db, attempt, question_info)
    except Exception:
        logger.exception("strategy.on_finish 失败（忽略）")

    return _serialize_finished(attempt)


# =========================================================
# 查询
# =========================================================
async def get_attempt(db: AsyncSession, attempt_id: int) -> dict:
    """读一条作答记录的完整快照（含 dialog 与所有评分字段）。"""
    attempt = await db.get(QuestionAttempt, attempt_id)
    if not attempt:
        raise ValueError("attempt 不存在")
    return {
        "attempt_id": attempt.id,
        "question_type": attempt.question_type,
        "question_id": attempt.question_id,
        "status": attempt.status,
        "final_score": attempt.final_score,
        "rubric_result": attempt.rubric_result or [],
        "overall_summary": attempt.overall_summary or "",
        "design_issues": attempt.design_issues or [],
        "extension_qa": attempt.extension_qa or [],
        "dialog": attempt.dialog or [],
        "follow_up_count": attempt.follow_up_count,
        "created_at": attempt.created_at.isoformat() if attempt.created_at else None,
        "finished_at": attempt.finished_at.isoformat() if attempt.finished_at else None,
    }


# =========================================================
# 内部工具
# =========================================================
def _serialize_finished(attempt: QuestionAttempt) -> dict:
    """将已结束的 attempt 打包为前端所需的 dict。"""
    return {
        "attempt_id": attempt.id,
        "status": attempt.status,
        "final_score": attempt.final_score,
        "rubric_result": attempt.rubric_result or [],
        "overall_summary": attempt.overall_summary or "",
        "design_issues": attempt.design_issues or [],
        "extension_qa": attempt.extension_qa or [],
        "dialog": attempt.dialog or [],
    }


def _safe_int(v: Any, *, default: int, lo: int, hi: int) -> int:
    """将任意值安全转 int 并刪减到 [lo, hi] 范围内，解析失败返 default。"""
    try:
        n = int(v)
    except (TypeError, ValueError):
        return default
    return max(lo, min(hi, n))


def dialog_to_text(dialog: list[dict]) -> str:
    """把 dialog 渲染成可读文本，喂给 LLM。"""
    lines: list[str] = []
    for m in dialog:
        role = m.get("role", "?")
        mtype = m.get("type", "")
        raw = m.get("content")
        # 范例反馈可能是 list[str]（新版分点），统一转成多行字符串
        if isinstance(raw, list):
            content = "\n".join(f"- {str(x).strip()}" for x in raw if str(x).strip())
        else:
            content = (raw or "").strip() if isinstance(raw, str) else ""
        if not content:
            continue
        if role == "agent":
            if mtype == "question":
                lines.append(f"[面试官 · 主问题] {content}")
            elif mtype == "follow_up":
                lines.append(f"[面试官 · 追问] {content}")
            elif mtype == "feedback":
                cov = "已覆盖" if m.get("covered") else "未覆盖"
                lines.append(f"[面试官 · 范例({cov})] {content}")
            else:
                lines.append(f"[面试官] {content}")
        else:
            lines.append(f"[候选人] {content}")
    return "\n".join(lines)
