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
    global_asked: list[str] = None,
) -> list[dict]:
    """
    一次性生成 3-5 道面试题 + Rubric

    Returns:
        [{"question": "...", "rubric": [{"key_point": "...", "score": 20}, ...]}, ...]
    """
    history_text = "（无）"
    if history:
        lines = []
        for i, h in enumerate(history, 1):
            missed = "、".join(h.get("missed", [])) or "无"
            lines.append(f"{i}. {h['question']}（得分{h['score']}，遗漏：{missed}）")
        history_text = "\n".join(lines)

    global_text = ""
    if global_asked:
        global_text = "\n\n## 其他知识点已出过的题目（不要重复）\n" + "\n".join(f"- {q}" for q in global_asked)

    prompt = GENERATE_QUESTION_PROMPT.format(
        knowledge_point=knowledge_point,
        history=history_text,
    ) + global_text

    llm = get_llm(temperature=0.3)
    response = await llm.ainvoke(prompt)

    try:
        result = parse_llm_json(response.content)
        questions = result.get("questions", [])
        # 兼容旧格式（单题）
        if not questions and result.get("question"):
            questions = [{"question": result["question"], "rubric": result.get("rubric", [])}]
        # 归一化每道题的 rubric
        for q in questions:
            rubric = q.get("rubric", [])
            if rubric:
                raw_total = sum(r["score"] for r in rubric)
                if raw_total != 100 and raw_total > 0:
                    for r in rubric:
                        r["score"] = round(r["score"] * 100 / raw_total)
                    diff = 100 - sum(r["score"] for r in rubric)
                    rubric[0]["score"] += diff
                q["rubric"] = rubric
        return questions
    except (json.JSONDecodeError, IndexError) as e:
        logger.error(f"出题 JSON 解析失败: {e}\nLLM 原始输出: {response.content}")
        return [{
            "question": f"{knowledge_point}的核心原理？",
            "rubric": [{"key_point": "核心概念", "score": 50}, {"key_point": "应用场景", "score": 50}],
        }]


async def score_answer_with_rubric(
    question: str,
    rubric_items: list[dict],
    user_answer: str,
    conversation_history: str = "",
) -> dict:
    """
    基于 Rubric 对用户回答打分，同时决定是否追问并生成学习小结

    Args:
        question: 题目内容
        rubric_items: Rubric 关键点列表 [{"key_point": "...", "score": 20}, ...]
        user_answer: 用户的回答
        conversation_history: 本轮完整问答链（用于生成整体总结）
    """
    rubric_text = "\n".join(
        f"- {item['key_point']}（{item['score']}分）"
        for item in rubric_items
    )

    history_section = ""
    if conversation_history:
        history_section = f"\n\n## 本轮完整问答记录（用于生成整体总结）\n{conversation_history}"

    prompt = RUBRIC_SCORING_PROMPT.format(
        question=question,
        rubric_items=rubric_text,
        user_answer=user_answer,
    ) + history_section

    llm = get_llm()
    response = await llm.ainvoke(prompt)

    try:
        result = parse_llm_json(response.content)
        # 确保 summary 字段存在
        if "summary" not in result:
            result["summary"] = []
        # 强制校正 total（防止 LLM 算错）
        items = result.get("items", [])
        rubric_total = sum(item["score"] for item in items)
        hit_total = sum(item["score"] for item in items if item.get("hit", False))
        result["total"] = hit_total
        result["rubric_total"] = rubric_total  # 满分是多少
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
