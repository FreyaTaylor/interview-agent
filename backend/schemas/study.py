"""
学习相关的请求/响应模型
"""
from pydantic import BaseModel


# ---- 请求模型 ----

class StartStudyRequest(BaseModel):
    """开始学习 — 选择知识点"""
    knowledge_point_id: int


class StartWithAnswerRequest(BaseModel):
    """从面试复盘进入学习 — 带用户回答"""
    knowledge_point_id: int
    user_answer: str = ""        # 面试中的回答摘要
    interview_questions: list[str] = []  # 面试中被问到的问题


class SubmitAnswerRequest(BaseModel):
    """提交回答（首次回答或追问回答通用）"""
    conversation_id: int
    answer: str


class NextQuestionRequest(BaseModel):
    """请求下一题"""
    conversation_id: int


# ---- 响应模型 ----

class RubricItemResult(BaseModel):
    """单个 Rubric 关键点的评分结果"""
    key_point: str
    score: int
    hit: bool
    matched_text: str = ""      # 候选人回答中命中的原文片段


class QuestionResponse(BaseModel):
    """出题响应"""
    conversation_id: int
    session_id: int
    knowledge_point_name: str
    question_content: str
    question_round: int


class ExtensionQuestion(BaseModel):
    """扩展题"""
    question: str
    answer: str


class ScoreResponse(BaseModel):
    """打分响应"""
    conversation_id: int
    total_score: int
    rubric_total: int = 100  # rubric 满分
    rubric_result: list[RubricItemResult]
    feedback: str
    recommended_answer: list[str] = []
    extension_questions: list[ExtensionQuestion] = []
    overall_summary: str = ""
    follow_up: str | None
    question_round: int


class KnowledgePointBrief(BaseModel):
    """知识点简要信息（用于列表展示）"""
    id: int
    name: str
    parent_name: str | None = None
    interview_weight: int
    mastery_level: int = 0
    study_count: int = 0
