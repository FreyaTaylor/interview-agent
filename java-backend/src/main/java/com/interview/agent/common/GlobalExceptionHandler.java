package com.interview.agent.common;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * 全局异常处理：所有异常统一转为 {@link ApiResponse}，HTTP 状态码始终 200
 * （前端按 code 字段判定业务结果，与 Python 端约定一致）。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 业务异常 — 已知错误码，按定义返回 */
    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Void>> handleBiz(BizException ex) {
        log.warn("[BizException] code={} msg={}", ex.getCode(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.OK)
                .body(ApiResponse.error(ex.getCode(), ex.getMessage()));
    }

    /** @Valid 参数校验失败 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
        String msg = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        log.warn("[Validation] {}", msg);
        return ResponseEntity.status(HttpStatus.OK)
                .body(ApiResponse.error(40001, "参数校验失败: " + msg));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraint(ConstraintViolationException ex) {
        log.warn("[ConstraintViolation] {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.OK)
                .body(ApiResponse.error(40001, "参数校验失败: " + ex.getMessage()));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParam(MissingServletRequestParameterException ex) {
        log.warn("[MissingParam] {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.OK)
                .body(ApiResponse.error(40001, "缺少参数: " + ex.getParameterName()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadable(HttpMessageNotReadableException ex) {
        log.warn("[BadRequest] {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.OK)
                .body(ApiResponse.error(40001, "请求体解析失败"));
    }

    /** 兜底 — 未知异常按 50000 返回，并打 stack trace */
    @ExceptionHandler(Throwable.class)
    public ResponseEntity<ApiResponse<Void>> handleUnknown(Throwable ex) {
        log.error("[Unknown] {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.OK)
                .body(ApiResponse.error(50000, "服务器内部错误: " + ex.getMessage()));
    }
}
