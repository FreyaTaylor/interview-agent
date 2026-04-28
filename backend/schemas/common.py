"""
通用响应模型
统一 API 响应格式：{"code": 0, "data": {...}, "message": "success"}
"""
from typing import Any, Generic, TypeVar
from pydantic import BaseModel

T = TypeVar("T")


class ApiResponse(BaseModel, Generic[T]):
    """统一 API 响应"""
    code: int = 0
    data: T | None = None
    message: str = "success"

    @classmethod
    def ok(cls, data: Any = None, message: str = "success") -> "ApiResponse":
        return cls(code=0, data=data, message=message)

    @classmethod
    def error(cls, code: int, message: str) -> "ApiResponse":
        return cls(code=code, data=None, message=message)
