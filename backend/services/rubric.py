"""
学习服务层
- 动态出题（LLM 生成题目 + Rubric）
- Rubric 评分（含追问决策 + 学习小结）
"""
import logging

from backend.services.llm import get_llm, parse_llm_json
from backend.prompts.study_prompts import GENERATE_QUESTION_PROMPT, RUBRIC_SCORING_PROMPT

logger = logging.getLogger(__name__)


async def generate_question(
    knowledge_point: str,
    history: list[dict],
) -> dict:
    """
    动态生成面试题 + Rubric

    Args:
        knowledge_point: 知识点名称
        history: 已考过的题目历史 [{"question": "...", "score": 80, "missed": ["遗漏点1"]}, ...]

    Returns:
        {"question": "题目内容", "rubric": [{"key_point": "...", "score": 20}, ...]}
    """
    history_text = "（无，这是第一题）"
    if history:
        lines = []
        for i, h in enumerate(history, 1):
            missed = "、".join(h.get("missed", [])) or "无"
            lines.append(f"{i}. 题目：{h['question']}，得分：{h['score']}/100，遗漏：{missed}")
        history_text = "\n".join(lines)

    prompt = GENERATE_QUESTION_PROMPT.format(
        knowledge_point=knowledge_point,
        history=history_text,
    )

    llm = get_llm(temperature=0.3)
    response = await llm.ainvoke(prompt)

    try:
        result = parse_llm_json(response.content)
        # 强制归一化 rubric 分值之和 = 100
        rubric = result.get("rubric", [])
        if rubric:
            raw_total = sum(r["score"] for r in rubric)
            if raw_total != 100 and raw_total > 0:
                for r in rubric:
                    r["score"] = round(r["score"] * 100 / raw_total)
                # 修正舍入误差
                diff = 100 - sum(r["score"] for r in rubric)
                rubric[0]["score"] += diff
            result["rubric"] = rubric
        return result
    except (json.JSONDecodeError, IndexError) as e:
        logger.error(f"出题 JSON 解析失败: {e}\nLLM 原始输出: {response.content}")
        return {
            "question": f"请描述一下{knowledge_point}的核心概念和应用场景。",
            "rubric": [{"key_point": "核心概念", "score": 50}, {"key_point": "应用场景", "score": 50}],
        }


async def score_answer_with_rubric(
    question: str,
    rubric_items: list[dict],
    user_answer: str,
) -> dict:
    """
    基于 Rubric 对用户回答打分，同时决定是否追问并生成学习小结

    Args:
        question: 题目内容
        rubric_items: Rubric 关键点列表 [{"key_point": "...", "score": 20}, ...]
        user_answer: 用户的回答

    Returns:
        评分结果 dict，包含 items, total, feedback, follow_up, follow_up_rubric, summary
    """
    rubric_text = "\n".join(
        f"- {item['key_point']}（{item['score']}分）"
        for item in rubric_items
    )

    prompt = RUBRIC_SCORING_PROMPT.format(
        question=question,
        rubric_items=rubric_text,
        user_answer=user_answer,
    )

    llm = get_llm()
    response = await llm.ainvoke(prompt)

    try:
        result = parse_llm_json(response.content)
        # 确保 summary 字段存在
        if "summary" not in result:
            result["summary"] = []
        # 强制校正 total = 所有命中项分值之和（防止 LLM 算错）
        items = result.get("items", [])
        rubric_total = sum(item["score"] for item in items)
        hit_total = sum(item["score"] for item in items if item.get("hit", False))
        # 如果 rubric 总分不是 100，按比例折算
        if rubric_total > 0 and rubric_total != 100:
            logger.warning(f"Rubric 总分 {rubric_total} ≠ 100，按比例折算")
            result["total"] = round(hit_total * 100 / rubric_total)
        else:
            result["total"] = hit_total
        return result
    except (json.JSONDecodeError, IndexError) as e:
        logger.error(f"Rubric 评分 JSON 解析失败: {e}\nLLM 原始输出: {response.content}")
        return {
            "items": [
                {"key_point": item["key_point"], "score": item["score"], "hit": False, "comment": "评分解析失败"}
                for item in rubric_items
            ],
            "total": 0,
            "feedback": "评分系统暂时出错，请重试。",
            "follow_up": None,
            "follow_up_rubric": [],
            "summary": [],
        }
