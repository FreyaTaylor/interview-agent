"""
应用配置管理
从环境变量或 .env 文件加载配置
"""
from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    """应用配置"""

    # 数据库
    DATABASE_URL: str = "postgresql+asyncpg://postgres:postgres@localhost:5432/interview_agent"

    # DeepSeek LLM
    DEEPSEEK_API_KEY: str = ""
    DEEPSEEK_BASE_URL: str = "https://api.deepseek.com/v1"
    DEEPSEEK_MODEL: str = "deepseek-chat"

    # DashScope Embedding（二期使用）
    DASHSCOPE_API_KEY: str = ""

    # 业务参数
    MAX_FOLLOW_UP_ROUNDS: int = 3  # 单题最大追问轮数

    model_config = {"env_file": ".env", "env_file_encoding": "utf-8"}


settings = Settings()
