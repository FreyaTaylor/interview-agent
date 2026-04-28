"""
Rubric 评分服务
调用 LLM 基于 Rubric 对用户回答进行结构化评分
"""
import json
import logging
from langchain_openai import ChatOpenAI

from backend.config import settings
from backend.prompts.study_prompts import RUBRIC_SCORING_PROMPT, EXPLORE_PROMPT

logger = logging.getLogger(__name__)


def _get_llm() -> ChatOpenAI:
    """获取 DeepSeek LLM 实例"""
    return ChatOpenAI(
        model=settings.DEEPSEEK_MODEL,
        api_key=settings.DEEPSEEK_API_KEY,
        base_url=settings.DEEPSEEK_BASE_URL,
        temperature=0.1,  # 评分需要稳定输出
    )


async def score_answer_with_rubric(
    question: str,
    rubric_items: list[dict],
    user_answer: str,
) -> dict:
    """
    基于 Rubric 对用户回答打分

    Args:
        question: 题目内容
        rubric_items: Rubric 关键点列表 [{"key_point": "...", "score": 20}, ...]
        user_answer: 用户的回答

    Returns:
        评分结果 dict: {"items": [...], "total": 60, "feedback": "..."}
    """
    # 格式化 Rubric 关键点为文本
    rubric_text = "\n".join(
        f"- {item['key_point']}（{item['score']}分）"
        for item in rubric_items
    )

    prompt = RUBRIC_SCORING_PROMPT.format(
        question=question,
        rubric_items=rubric_text,
        user_answer=user_answer,
    )

    llm = _get_llm()
    response = await llm.ainvoke(prompt)

    # 解析 LLM 返回的 JSON
    try:
        # 尝试从 markdown 代码块中提取 JSON
        content = response.content.strip()
        if "```json" in content:
            content = content.split("```json")[1].split("```")[0].strip()
        elif "```" in content:
            content = content.split("```")[1].split("```")[0].strip()

        result = json.loads(content)
        return result
    except (json.JSONDecodeError, IndexError) as e:
        logger.error(f"Rubric 评分 JSON 解析失败: {e}\nLLM 原始输出: {response.content}")
        # 降级：返回全部未命中
        return {
            "items": [
                {"key_point": item["key_point"], "score": item["score"], "hit": False, "comment": "评分解析失败"}
                for item in rubric_items
            ],
            "total": 0,
            "feedback": "评分系统暂时出错，请重试。",
        }


async def handle_explore_question(
    question: str,
    user_answer: str,
    score: int,
    chat_history: str,
    explore_question: str,
) -> str:
    """
    处理自由探索追问

    Args:
        question: 原始题目
        user_answer: 用户的原始回答
        score: Rubric 得分
        chat_history: 之前的探索对话历史
        explore_question: 用户的追问

    Returns:
        Agent 的回答文本
    """
    prompt = EXPLORE_PROMPT.format(
        question=question,
        user_answer=user_answer,
        score=score,
        chat_history=chat_history if chat_history else "（无）",
        explore_question=explore_question,
    )

    llm = _get_llm()
    # 探索回答可以稍微有创意
    llm.temperature = 0.3
    response = await llm.ainvoke(prompt)
    return response.content.strip()
