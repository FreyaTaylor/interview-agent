package com.interview.agent.infra.async;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * JDK 21 虚拟线程执行器。
 *
 * Spring MVC 已通过 {@code spring.threads.virtual.enabled=true} 把 Tomcat 请求线程切到虚拟线程，
 * 这里再额外提供一个命名 Bean，用于业务侧 fire-and-forget / 手动并发编排
 * （如 Project Grilling 异步落库）。
 */
@Configuration
public class VirtualThreadConfig {

    /** 业务用虚拟线程池 — 注入名 {@code virtualThreadExecutor}。 */
    @Bean(name = "virtualThreadExecutor", destroyMethod = "shutdown")
    public ExecutorService virtualThreadExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
