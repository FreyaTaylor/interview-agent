"""
学习相关的请求/响应模型
"""
from pydantic import BaseModel


# ---- 请求模型 ----

class StartStudyRequest(BaseModel):
    """开始学习 — 选择知识点"""
    knowledge_point_id: int


class SubmitAnswerRequest(BaseModel):
    """提交回答"""
    conversation_id: int
    answer: str


class ExploreRequest(BaseModel):
    """自由探索追问"""
    conversation_id: int
    question: str


# ---- 响应模型 ----

class RubricItemResult(BaseModel):
    """单个 Rubric 关键点的评分结果"""
    key_point: str
    score: int
    hit: bool
    comment: str


class QuestionResponse(BaseModel):
    """出题响应"""
    conversation_id: int
    session_id: int
    knowledge_point_name: str
    question_id: int
    question_content: str


class ScoreResponse(BaseModel):
    """打分响应"""
    conversation_id: int
    total_score: int
    rubric_result: list[RubricItemResult]
    feedback: str


class ExploreResponse(BaseModel):
    """探索回答响应"""
    conversation_id: int
    answer: str
    explore_count: int
    max_explore: int


class KnowledgePointBrief(BaseModel):
    """知识点简要信息（用于列表展示）"""
    id: int
    name: str
    parent_name: str | None = None
    interview_weight: int
    mastery_level: int = 0
    study_count: int = 0
