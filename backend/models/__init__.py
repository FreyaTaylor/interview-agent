"""
SQLAlchemy 模型汇总导入
所有模型在此统一导入，确保 Base.metadata 包含所有表定义
"""
from backend.models.base import Base
from backend.models.user import User
from backend.models.knowledge import KnowledgeNode
from backend.models.study import (
    StudySession, Conversation, ConversationMessage,
    MasteryRecord, MasteryHistory,
)
from backend.models.interview import InterviewRecord, AlgorithmQuestion, HrQuestion

__all__ = [
    "Base",
    "User",
    "KnowledgeNode",
    "StudySession", "Conversation", "ConversationMessage",
    "MasteryRecord", "MasteryHistory",
    "InterviewRecord", "AlgorithmQuestion", "HrQuestion",
]
