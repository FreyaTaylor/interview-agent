package com.interview.agent.common;

/**
 * 统一响应体：{ "code": 0, "data": ..., "message": "success" }
 *
 * 错误码约定（与 Python 端对齐）：
 *   - 0       成功
 *   - 40001   参数校验失败 / 业务校验失败
 *   - 40004   资源不存在
 *   - 50000   服务器内部错误
 */
public record ApiResponse<T>(int code, T data, String message) {

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(0, data, "success");
    }

    public static <T> ApiResponse<T> success() {
        return new ApiResponse<>(0, null, "success");
    }

    public static <T> ApiResponse<T> error(int code, String message) {
        return new ApiResponse<>(code, null, message);
    }
}
