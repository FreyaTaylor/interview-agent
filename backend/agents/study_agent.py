"""
学习对话 Agent — Phase 0
使用 LangGraph 实现 ReAct 循环：出题 → 回答 → Rubric 打分 → 自由探索

Phase 0 简化策略：
- 不使用 LangGraph 的 interrupt 机制（API 驱动的分步调用更适合 web 场景）
- 每个 API 请求调用一个 agent 节点函数
- 状态通过数据库持久化（conversation + conversation_message 表）
- LangGraph 图用于定义流程结构和路由逻辑

后续 Phase 1 可以引入 checkpointer 和更复杂的条件边
"""
import logging
from typing import TypedDict, Literal

from langgraph.graph import StateGraph, END

from backend.services.rubric import score_answer_with_rubric, handle_explore_question
from backend.config import settings

logger = logging.getLogger(__name__)


# ---- State 定义 ----

class StudyState(TypedDict):
    """学习对话 Agent 的状态"""
    # 输入
    action: str                     # "start" | "answer" | "explore"
    knowledge_point_id: int
    knowledge_point_name: str
    user_input: str                 # 用户的回答或追问

    # 题目信息
    question_id: int
    question_content: str
    rubric_items: list[dict]        # [{"key_point": "...", "score": 20}, ...]

    # 评分结果
    score: int
    rubric_result: dict             # LLM 返回的完整评分 JSON
    feedback: str

    # 探索状态
    explore_count: int
    explore_history: str            # 探索对话历史（拼接文本）

    # 输出
    agent_response: str
    phase: str                      # 当前阶段："questioning" | "scored" | "exploring"


# ---- 节点函数 ----

async def generate_question_node(state: StudyState) -> dict:
    """
    出题节点
    Phase 0：从已有题目中选题（数据库查询在 API 层完成，此处只格式化）
    """
    return {
        "agent_response": state["question_content"],
        "phase": "questioning",
        "explore_count": 0,
        "explore_history": "",
    }


async def score_answer_node(state: StudyState) -> dict:
    """
    打分节点 — 调用 LLM 基于 Rubric 评分
    """
    logger.info(f"正在评分: 知识点={state['knowledge_point_name']}, 问题ID={state['question_id']}")

    result = await score_answer_with_rubric(
        question=state["question_content"],
        rubric_items=state["rubric_items"],
        user_answer=state["user_input"],
    )

    return {
        "score": result.get("total", 0),
        "rubric_result": result,
        "feedback": result.get("feedback", ""),
        "agent_response": result.get("feedback", ""),
        "phase": "scored",
    }


async def handle_explore_node(state: StudyState) -> dict:
    """
    自由探索节点 — 回答用户的追问
    """
    current_count = state.get("explore_count", 0)

    # 检查是否超过探索上限
    if current_count >= settings.MAX_EXPLORE_ROUNDS:
        return {
            "agent_response": f"探索已达上限（{settings.MAX_EXPLORE_ROUNDS}轮），建议进入下一个知识点继续学习。",
            "phase": "exploring",
            "explore_count": current_count,
        }

    answer = await handle_explore_question(
        question=state["question_content"],
        user_answer=state.get("user_input", ""),
        score=state.get("score", 0),
        chat_history=state.get("explore_history", ""),
        explore_question=state["user_input"],
    )

    # 更新探索历史
    new_history = state.get("explore_history", "")
    new_history += f"\n用户追问：{state['user_input']}\nAgent回答：{answer}\n"

    return {
        "agent_response": answer,
        "phase": "exploring",
        "explore_count": current_count + 1,
        "explore_history": new_history,
    }


# ---- 路由函数 ----

def route_action(state: StudyState) -> str:
    """根据 action 路由到对应节点"""
    action = state.get("action", "start")
    if action == "start":
        return "generate_question"
    elif action == "answer":
        return "score_answer"
    elif action == "explore":
        return "handle_explore"
    return "generate_question"


# ---- 构建 Graph ----

def build_study_graph() -> StateGraph:
    """构建学习对话的 LangGraph 状态图"""
    builder = StateGraph(StudyState)

    # 添加节点
    builder.add_node("generate_question", generate_question_node)
    builder.add_node("score_answer", score_answer_node)
    builder.add_node("handle_explore", handle_explore_node)

    # 入口路由：根据 action 分发到不同节点
    builder.add_conditional_edges("__start__", route_action)

    # 每个节点执行后结束（等待下一次 API 调用）
    builder.add_edge("generate_question", END)
    builder.add_edge("score_answer", END)
    builder.add_edge("handle_explore", END)

    return builder.compile()


# 全局 graph 实例
study_graph = build_study_graph()
