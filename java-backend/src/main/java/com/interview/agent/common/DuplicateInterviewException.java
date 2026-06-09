package com.interview.agent.common;

import com.interview.agent.interview.dto.CheckDuplicateResponse;

/**
 * 面试文本重复异常 —— 预解析阶段检测到相同输入文本已落库时抛出。
 *
 * <p>由 {@link GlobalExceptionHandler} 统一转为 {@code {code: 40009, data: 重复记录信息, message}}，
 * 前端据此弹出「覆盖 / 取消」对话框。区别于普通 {@link BizException}：它需要携带重复记录的结构化 data，
 * 让前端能展示旧记录的公司 / 岗位 / 评分 / 时间。
 */
public class DuplicateInterviewException extends RuntimeException {

    /** 业务错误码：面试文本重复。 */
    public static final int CODE = 40009;

    private final transient CheckDuplicateResponse info;

    public DuplicateInterviewException(CheckDuplicateResponse info) {
        super("该面试文本已上传过");
        this.info = info;
    }

    public CheckDuplicateResponse info() {
        return info;
    }
}
