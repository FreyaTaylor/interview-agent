"""
项目答题画像服务（重构版）

职责：
- 维护 ProjectUserProfile.project_facts / weak_points（乐观锁）
- 答题后异步抽取：从本轮 Q&A 提取事实/薄弱点
- 渲染画像 block 注入 Agent prompt

接入方式（重要）：
- extract_and_apply 应被异步触发（asyncio.create_task），**必须用独立 session**
- 调用方传入的是 project_id/round/topic/q/a/summary，本服务自行打开 DB 会话
"""
from __future__ import annotations

import logging

from sqlalchemy import select, update
from sqlalchemy.ext.asyncio import AsyncSession

from backend.database import async_session_factory
from backend.models.project import Project, ProjectUserProfile
from backend.prompts.extract_profile_prompt import EXTRACT_PROFILE_PROMPT
from backend.services.llm import get_llm, parse_llm_json

logger = logging.getLogger(__name__)

MAX_WEAK_POINTS = 20
MAX_FACTS = 50         # 扁平 facts 列表上限（超出时保留最新）
MAX_RETRY = 3


# =========================
# 基础：读 / 建
# =========================
async def get_or_create_profile(
    db: AsyncSession, project_id: int, user_id: int = 1
) -> ProjectUserProfile:
    """读取某人某项目的画像行；不存在则创建空画像。调用方：ProjectGrillingPage 加载项目、拷打打分完成后抽取。"""
    profile = (await db.execute(
        select(ProjectUserProfile).where(
            ProjectUserProfile.project_id == project_id,
            ProjectUserProfile.user_id == user_id,
        )
    )).scalar_one_or_none()
    if profile:
        return profile

    profile = ProjectUserProfile(
        project_id=project_id, user_id=user_id,
        project_facts=[], weak_points=[], version=0,
    )
    db.add(profile)
    await db.commit()
    await db.refresh(profile)
    return profile


# =========================
# 纯函数：合并 patch
# =========================
def _apply_facts_patch(facts: list, patch: dict) -> list:
    """facts: list[str]，每条是一段完整事实描述。

    patch: {
      "add":    ["新 fact", ...],
      "update": [{"old": "原文", "new": "改写后"}, ...],
      "remove": ["原文", ...]
    }
    操作顺序：先 update（按 old 原文匹配并就地替换），再 remove，再 add（去重 + 截断）。
    """
    new_facts: list[str] = [str(x) for x in (facts or []) if x]
    patch = patch or {}

    # 1) update：按 old 原文精确匹配并就地替换
    for op in (patch.get("update") or []):
        old = (op.get("old") or "").strip()
        new = (op.get("new") or "").strip()
        if not old or not new:
            continue
        for i, item in enumerate(new_facts):
            if item.strip() == old:
                new_facts[i] = new
                break
        else:
            # 没匹配到旧条目：当成 add 兜底，避免信息丢失
            new_facts.append(new)

    # 2) remove：按原文精确匹配
    rm = {(x or "").strip() for x in (patch.get("remove") or [])}
    if rm:
        new_facts = [item for item in new_facts if item.strip() not in rm]

    # 3) add：去重
    existing = {item.strip() for item in new_facts}
    for item in (patch.get("add") or []):
        item = (item or "").strip()
        if item and item not in existing:
            new_facts.append(item)
            existing.add(item)

    # 4) 截断：超出上限保留最新
    return new_facts[-MAX_FACTS:]


def _apply_weak_points_patch(
    weak_points: list, add_items: list, resolved: list
) -> list:
    """合并 weak_points：移除 resolved 中的项 → 追加 add_items（按 point 文本去重）→ 上限 MAX_WEAK_POINTS。"""
    new_list = list(weak_points or [])
    resolved_set = {(x or "").strip() for x in (resolved or [])}
    if resolved_set:
        new_list = [wp for wp in new_list if (wp.get("point") or "").strip() not in resolved_set]

    existing_points = {(wp.get("point") or "").strip() for wp in new_list}
    for item in (add_items or []):
        point = (item.get("point") or "").strip()
        if not point or point in existing_points:
            continue
        new_list.append({
            "topic": (item.get("topic") or "").strip(),
            "point": point,
            "question": (item.get("question") or "").strip(),
            "round": item.get("round"),
        })
        existing_points.add(point)
    return new_list[-MAX_WEAK_POINTS:]


# =========================
# 抽取：调 LLM 出 patch
# =========================
def _format_facts_for_prompt(facts: list) -> str:
    """将 facts 渲染为带序号的文本，供 LLM prompt 引用。"""
    if not facts:
        return "（暂无）"
    return "\n".join(f"{i+1}. {item}" for i, item in enumerate(facts) if item)


def _format_weak_points_for_prompt(weak_points: list) -> str:
    """将 weak_points 按 [topic] point（R轮次）格式输出。"""
    if not weak_points:
        return "（暂无）"
    return "\n".join(
        f"- [{wp.get('topic') or '?'}] {wp.get('point')}（R{wp.get('round')}）"
        for wp in weak_points
    )


async def _call_extract_llm(
    project_name: str,
    project_description: str,
    facts: list,
    weak_points: list,
    topic: str,
    question: str,
    answer: str,
    scoring_summary: str,
    missed_key_points: list,
) -> dict | None:
    """调起 EXTRACT_PROFILE_PROMPT，让 LLM 返回画像 patch JSON，解析失败返 None。"""
    prompt = EXTRACT_PROFILE_PROMPT.format(
        project_name=project_name or "",
        project_description=project_description or "",
        current_facts=_format_facts_for_prompt(facts),
        current_weak_points=_format_weak_points_for_prompt(weak_points),
        topic=topic or "未分类",
        question=question or "",
        answer=answer or "",
        scoring_summary=scoring_summary or "",
        missed_key_points="、".join(missed_key_points) if missed_key_points else "（无）",
    )
    llm = get_llm(temperature=0.2)
    try:
        resp = await llm.ainvoke(prompt)
        content = getattr(resp, "content", str(resp))
        return parse_llm_json(content)
    except Exception as e:
        logger.warning("extract_profile LLM 调用失败: %s", e)
        return None


# =========================
# 主入口：异步抽取并落库
# =========================
async def extract_and_apply(
    project_id: int,
    topic: str,
    question: str,
    answer: str,
    scoring_summary: str = "",
    missed_key_points: list[str] | None = None,
    user_id: int = 1,
) -> None:
    """整体流程（独立 DB 会话，可作为 asyncio.create_task 触发）：
       1. 读 profile + 项目元数据
       2. 调 LLM 出 patch
       3. 乐观锁 apply facts / weak_points
    """
    async with async_session_factory() as db:
        project = (await db.execute(
            select(Project).where(Project.id == project_id)
        )).scalar_one_or_none()
        if not project:
            logger.warning("extract_and_apply: project %s 不存在", project_id)
            return

        for attempt in range(MAX_RETRY):
            profile = await get_or_create_profile(db, project_id, user_id)

            patch = await _call_extract_llm(
                project_name=project.name,
                project_description=project.description or "",
                facts=profile.project_facts or [],
                weak_points=profile.weak_points or [],
                topic=topic,
                question=question,
                answer=answer,
                scoring_summary=scoring_summary,
                missed_key_points=missed_key_points or [],
            )
            if not patch:
                return

            new_facts = _apply_facts_patch(
                profile.project_facts or [], patch.get("facts_patch") or {}
            )
            new_weak = _apply_weak_points_patch(
                profile.weak_points or [],
                patch.get("weak_points_add") or [],
                patch.get("weak_points_resolved") or [],
            )
            old_version = profile.version

            result = await db.execute(
                update(ProjectUserProfile)
                .where(
                    ProjectUserProfile.id == profile.id,
                    ProjectUserProfile.version == old_version,
                )
                .values(
                    project_facts=new_facts,
                    weak_points=new_weak,
                    version=old_version + 1,
                )
            )
            await db.commit()

            if result.rowcount == 1:
                return

            # 版本冲突：让 ORM 下一轮 get_or_create_profile 重新加载最新版本
            # 注意：AsyncSession.expire 是同步方法，不能 await
            db.expire(profile)
            logger.info("ProjectUserProfile %s 版本冲突，重试 %d", profile.id, attempt + 1)

        logger.warning("extract_and_apply 在 %d 次重试后仍失败 (project=%s)",
                       MAX_RETRY, project_id)


# =========================
# 渲染：注入 Agent prompt
# =========================
def render_for_prompt(
    profile: ProjectUserProfile | None, current_dimension: str | None = None
) -> str:
    """生成可塞进 LLM prompt 的画像 block（中文，仅 facts + weak_points）。"""
    if not profile:
        return ""

    facts: list = profile.project_facts or []
    weak_points: list = profile.weak_points or []
    if not facts and not weak_points:
        return ""

    lines = ["【用户在本项目的答题画像（仅供出题/追问参考）】"]
    if facts:
        lines.append("### 项目事实")
        lines.extend(f"  · {item}" for item in facts if item)

    if weak_points:
        lines.append("### 已暴露的薄弱点")
        cur = [wp for wp in weak_points if current_dimension and wp.get("topic") == current_dimension]
        others = [wp for wp in weak_points if wp not in cur]
        for wp in (cur + others)[:10]:
            tag = wp.get("topic") or "?"
            lines.append(f"  · [{tag}] {wp.get('point')}")

    return "\n".join(lines)
