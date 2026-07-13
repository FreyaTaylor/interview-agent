package com.interview.agent.auth;

/**
 * 测试专用：暴露包级私有的 {@link CurrentUser#set}/{@link CurrentUser#clear}，
 * 让服务层集成测试能在没有 AuthInterceptor 的情况下设置当前用户。
 */
public final class CurrentUserTestSupport {

    private CurrentUserTestSupport() {
    }

    public static void set(long userId) {
        CurrentUser.set(userId);
    }

    public static void clear() {
        CurrentUser.clear();
    }
}
