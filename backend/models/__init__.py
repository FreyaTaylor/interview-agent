"""
SQLAlchemy 模型汇总导入
所有模型在此统一导入，确保 Base.metadata 包含所有表定义
"""
from backend.models.base import Base
from backend.models.user import User
from backend.models.knowledge import KnowledgeNode
from backend.models.qa import StudyQuestion, QuestionAttempt
from backend.models.interview import (
    InterviewRecord,
    InterviewKnowledgeQuestion, InterviewProjectQuestion, InterviewOtherQuestion,
    UserAnswerEmbedding,
)
from backend.models.learn import KnowledgeContent, LearnChat
from backend.models.project import Project, ProjectUserProfile
from backend.models.project_node import ProjectNode

__all__ = [
    "Base",
    "User",
    "KnowledgeNode",
    "ProjectNode",
    "StudyQuestion", "QuestionAttempt",
    "InterviewRecord",
    "InterviewKnowledgeQuestion", "InterviewProjectQuestion", "InterviewOtherQuestion",
    "ProjectUserProfile",
    "UserAnswerEmbedding",
    "KnowledgeContent", "LearnChat",
    "Project",
]
