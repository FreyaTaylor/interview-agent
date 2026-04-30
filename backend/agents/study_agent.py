"""
学习对话 Agent — 动态出题版
使用 LangGraph 实现：动态出题 → 回答 → Rubric 打分(含追问决策) → 下一题

核心变化（相比 Phase 0）：
- 题目和 Rubric 由 LLM 动态生成，不再依赖数据库预存
- 评分后 LLM 自动决定是否追问，取消用户主导的自由探索
- 追问的回答也要分点打分
- 每题评分后生成学习小结（落库）
"""
import logging
from typing import TypedDict

from langgraph.graph import StateGraph, END

from backend.services.rubric import generate_question, score_answer_with_rubric

logger = logging.getLogger(__name__)


# ---- State 定义 ----

class StudyState(TypedDict):
    """学习对话 Agent 的状态"""
    # 输入
    action: str                     # "start" | "answer" | "next"
    knowledge_point_name: str
    user_input: str                 # 用户的回答

    # 历史（已考过的题目 + 得分 + 遗漏点）
    question_history: list[dict]    # [{"question": "...", "score": 80, "missed": [...]}, ...]

    # 当前题目
    question_content: str
    rubric_items: list[dict]        # [{"key_point": "...", "score": 20}, ...]

    # 评分结果
    score: int
    rubric_result: dict             # LLM 返回的完整评分 JSON
    feedback: str
    follow_up: str | None           # LLM 决定的追问（None 表示不追问）
    follow_up_rubric: list[dict]    # 追问的 Rubric
    summary: list[str]              # 本题学习小结

    # 输出
    agent_response: str
    phase: str                      # "questioning" | "scored"


# ---- 节点函数 ----

async def generate_question_node(state: StudyState) -> dict:
    """
    出题节点 — 调用 LLM 动态生成题目 + Rubric
    """
    logger.info(f"动态出题: 知识点={state['knowledge_point_name']}, 历史题数={len(state.get('question_history', []))}")

    result = await generate_question(
        knowledge_point=state["knowledge_point_name"],
        history=state.get("question_history", []),
    )

    return {
        "question_content": result["question"],
        "rubric_items": result.get("rubric", []),
        "agent_response": result["question"],
        "phase": "questioning",
        "score": 0,
        "rubric_result": {},
        "feedback": "",
        "follow_up": None,
        "follow_up_rubric": [],
        "summary": [],
    }


async def score_answer_node(state: StudyState) -> dict:
    """
    打分节点 — 调用 LLM 基于 Rubric 评分，同时决定是否追问并生成小结
    """
    logger.info(f"正在评分: 知识点={state['knowledge_point_name']}")

    result = await score_answer_with_rubric(
        question=state["question_content"],
        rubric_items=state["rubric_items"],
        user_answer=state["user_input"],
    )

    return {
        "score": result.get("total", 0),
        "rubric_result": result,
        "feedback": result.get("feedback", ""),
        "follow_up": result.get("follow_up"),
        "follow_up_rubric": result.get("follow_up_rubric", []),
        "summary": result.get("summary", []),
        "agent_response": result.get("feedback", ""),
        "phase": "scored",
    }


# ---- 路由函数 ----

def route_action(state: StudyState) -> str:
    """根据 action 路由到对应节点"""
    action = state.get("action", "start")
    if action in ("start", "next"):
        return "generate_question"
    elif action == "answer":
        return "score_answer"
    return "generate_question"


# ---- 构建 Graph ----

def build_study_graph() -> StateGraph:
    """构建学习对话的 LangGraph 状态图"""
    builder = StateGraph(StudyState)

    # 添加节点
    builder.add_node("generate_question", generate_question_node)
    builder.add_node("score_answer", score_answer_node)

    # 入口路由：根据 action 分发
    builder.add_conditional_edges("__start__", route_action)

    # 每个节点执行后结束（等待下一次 API 调用）
    builder.add_edge("generate_question", END)
    builder.add_edge("score_answer", END)

    return builder.compile()


# 全局 graph 实例
study_graph = build_study_graph()
