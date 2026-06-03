"""
面试评分 — 单题评分 + 批量评分（顺便更新掌握度）+ 整体分析
"""
import logging

from sqlalchemy.ext.asyncio import AsyncSession

from backend.services.llm import get_llm, parse_llm_json
from backend.prompts.interview_prompts import (
    INTERVIEW_SCORE_PROMPT,
    INTERVIEW_PROJECT_SCORE_PROMPT,
    INTERVIEW_ALGORITHM_SCORE_PROMPT,
    INTERVIEW_HR_SCORE_PROMPT,
    INTERVIEW_OVERALL_ANALYSIS_PROMPT,
)

logger = logging.getLogger(__name__)


# ============================================================
# 单题评分
# ============================================================

async def score_interview_group(group: dict) -> dict | None:
    """
    按 type 走不同 prompt：
      knowledge → rubric 评分（含 total_score）
      project   → 面试官印象（rating + highlights/improvements/...）
      algorithm → 解题点评 + 题目描述
      hr        → 表现反馈 + 建议
    user_answer 为空时跳过（避免给 LLM 空输入）。
    """
    g_type = group.get("type")
    questions_text = "\n".join(f"- {q}" for q in group.get("questions", []))
    llm = get_llm(temperature=0.1)
    user_answer = (group.get("user_answer") or "").strip()
    # 未作答占位：仍走打分流程（应给低分 + 推荐答案），不再 return None
    answer_for_prompt = user_answer or "（候选人未作答此问题，请按未作答处理：给 0 分，并在反馈中说明未作答，同时给出标准答案要点）"

    try:
        if g_type == "knowledge":
            prompt = INTERVIEW_SCORE_PROMPT.format(
                knowledge_point=group.get("knowledge_point", ""),
                questions=questions_text,
                user_answer=answer_for_prompt,
                original_dialogue=group.get("original_dialogue", "") or "无",
            )
        elif g_type == "project":
            prompt = INTERVIEW_PROJECT_SCORE_PROMPT.format(
                project_name=group.get("project_name", "项目"),
                topic=group.get("topic", "拷打"),
                questions=questions_text,
                user_answer=answer_for_prompt,
            )
        elif g_type == "algorithm":
            # 算法题即使没作答也评一下（给出题面/建议解法）
            prompt = INTERVIEW_ALGORITHM_SCORE_PROMPT.format(
                title=group.get("title", "未知算法题"),
                user_answer=user_answer or "未提供解题过程",
                original_dialogue=group.get("original_dialogue", "") or "无",
            )
        elif g_type == "hr":
            prompt = INTERVIEW_HR_SCORE_PROMPT.format(
                questions=questions_text,
                user_answer=answer_for_prompt,
            )
        else:
            return None

        response = await llm.ainvoke(prompt)
        result = parse_llm_json(response.content)

        if g_type == "project":
            return {
                "type": "project",
                "rating": result.get("rating", 3),
                "rating_label": result.get("rating_label", ""),
                "impression": result.get("impression", ""),
                "highlights": result.get("highlights", []),
                "improvements": result.get("improvements", []),
                "follow_up_risks": result.get("follow_up_risks", []),
                "suggested_answer": result.get("suggested_answer", []),
            }
        if g_type == "algorithm":
            # leetcode_url/title 由 parser 的 LeetCode skill 提供，这里只取解题点评
            return {
                "type": "algorithm",
                "feedback": result.get("feedback", ""),
                "description": result.get("description", ""),
                "example": result.get("example", ""),
                "suggested_approach": result.get("suggested_approach", ""),
            }
        if g_type == "hr":
            return {
                "type": "hr",
                "feedback": result.get("feedback", ""),
                "suggestion": result.get("suggestion", ""),
            }
        # knowledge
        return {
            "type": "knowledge",
            "total_score": result.get("total_score", 0),
            "feedback": result.get("feedback", ""),
            "rubric_result": result.get("items", []),
            "recommended_answer": result.get("recommended_answer", []),
        }
    except Exception as e:
        logger.error(f"面试评分失败: {e}")
        return None


# ============================================================
# 批量评分 + 掌握度更新
# ============================================================

async def score_all_groups(
    enriched_groups: list[dict],
    db: AsyncSession,
) -> tuple[list[dict], int, int]:
    """
    对所有可评分分组评分。
    返回: (scored_groups, knowledge 总分之和, knowledge 已评分数)

    旧版本会顺便按 EMA 更新 MasteryRecord，新模型下 mastery 完全派生自
    question_attempt，本函数不再写任何掌握度表。

    并发优化：LLM 评分调用走 asyncio.gather + Semaphore（默认 5 并发）。
    """
    import asyncio

    scored_groups: list[dict] = [dict(g) for g in enriched_groups]
    scorable = {"knowledge", "project", "algorithm", "hr"}
    sem = asyncio.Semaphore(5)  # DeepSeek 并发上限，避免触发 QPS 限额

    async def _score(g: dict) -> dict | None:
        async with sem:
            try:
                return await score_interview_group(g)
            except Exception as e:
                logger.error(f"评分失败: {g.get('knowledge_point', g.get('project_name'))}: {e}")
                return None

    # Phase A: 并发跑所有可评分组的 LLM 调用
    score_tasks = [
        _score(g) if g.get("type") in scorable else asyncio.sleep(0, result=None)
        for g in scored_groups
    ]
    results = await asyncio.gather(*score_tasks)

    # Phase B: 聚合 knowledge 维度统计（不再写任何 mastery 表）
    total_score_sum = 0
    scored_count = 0
    for g, sr in zip(scored_groups, results):
        g["score_result"] = sr
        if sr and sr.get("type") == "knowledge":
            total_score_sum += sr.get("total_score", 0)
            scored_count += 1

    return scored_groups, total_score_sum, scored_count


# ============================================================
# 整体分析（面试官视角的通过率评估）
# ============================================================

async def generate_overall_analysis(
    scored_groups: list[dict],
    company: str = "",
    position: str = "",
) -> dict | None:
    """对所有评分结果生成整场面试的整体分析（通过概率 + 评价）。"""
    lines: list[str] = []
    for g in scored_groups:
        t = g.get("type", "other")
        sr = g.get("score_result")
        if t == "knowledge":
            score_info = f"（{sr['total_score']}分）" if sr and sr.get("total_score") else "（未评分）"
            lines.append(f"📖 知识点：{g.get('knowledge_point', '?')} {score_info}")
        elif t == "project":
            rating = f"（{'⭐' * sr.get('rating', 0)}）" if sr and sr.get("rating") else "（未评分）"
            lines.append(f"🔨 项目拷打：{g.get('project_name', '?')} · {g.get('topic', '?')} {rating}")
        elif t == "algorithm":
            lines.append(f"💻 算法题：{g.get('title', '?')}")
        elif t == "hr":
            qs = ", ".join(g.get("questions", [])[:2])
            lines.append(f"💬 HR题：{qs}")

    prompt = INTERVIEW_OVERALL_ANALYSIS_PROMPT.format(
        company=company or "未知",
        position=position or "未知",
        scored_summary="\n".join(lines) if lines else "无有效数据",
    )
    try:
        response = await get_llm(temperature=0.3).ainvoke(prompt)
        return parse_llm_json(response.content)
    except Exception as e:
        logger.error(f"整体分析生成失败: {e}")
        return None
