package com.interview.agent.auth;

/**
 * 当前登录用户的请求级上下文（ThreadLocal）。
 *
 * <p>由 {@link AuthInterceptor} 在每个受保护请求进入时写入、请求结束后清除；
 * 业务层通过 {@link #id()} 读取当前 user_id，替代一期写死的 {@code USER_ID = 1L}。
 *
 * <h3>虚拟线程说明</h3>
 * 项目开启了 {@code spring.threads.virtual.enabled}，每个请求独占一条（虚拟）线程，
 * ThreadLocal 天然隔离。但 <b>fire-and-forget 异步任务</b>（如 Project Grilling 的后台
 * 画像提取）运行在另一条线程上，<b>读不到</b>本 ThreadLocal —— 必须在派发前用
 * {@link #id()} 取出 userId，作为参数显式传入异步方法。
 */
public final class CurrentUser {

    private static final ThreadLocal<Long> HOLDER = new ThreadLocal<>();

    private CurrentUser() {
    }

    /** 写入当前请求的 user_id（由拦截器调用）。 */
    static void set(long userId) {
        HOLDER.set(userId);
    }

    /** 清除（由拦截器在 afterCompletion 调用，防止虚拟线程复用串号）。 */
    static void clear() {
        HOLDER.remove();
    }

    /**
     * 在指定 user 上下文中执行任务——把请求线程的登录身份透传到异步/工作线程。
     *
     * <p>用于 SSE 流式等"controller 取 userId → 交给 worker 线程跑业务"的场景：
     * worker 线程读不到请求线程的 ThreadLocal，需在此显式设入、执行完还原。
     */
    public static void runWith(long userId, Runnable task) {
        Long prev = HOLDER.get();
        HOLDER.set(userId);
        try {
            task.run();
        } finally {
            if (prev == null) {
                HOLDER.remove();
            } else {
                HOLDER.set(prev);
            }
        }
    }

    /**
     * 取当前登录用户 id。
     *
     * @return 已登录用户的 user_id
     * @throws IllegalStateException 未登录（上下文为空）—— 受保护接口理应已被拦截器拦下，
     *                               走到这里说明调用发生在请求线程之外或放行配置有误
     */
    public static long id() {
        Long v = HOLDER.get();
        if (v == null) {
            throw new IllegalStateException("当前线程无登录用户上下文（CurrentUser 未设置）");
        }
        return v;
    }

    /** 取当前 user_id；无上下文时返回 null（供少数允许匿名的场景判断）。 */
    public static Long idOrNull() {
        return HOLDER.get();
    }
}
