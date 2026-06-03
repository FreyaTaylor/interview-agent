package com.interview.agent.common;

/**
 * 业务异常 — 通过 {@link GlobalExceptionHandler} 统一转换为 {@link ApiResponse}。
 *
 * 用法：
 *   throw new BizException(40004, "知识点不存在: id=" + id);
 */
public class BizException extends RuntimeException {

    private final int code;

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    public BizException(int code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
