package com.interview.agent.common;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * 健康检查 — S0 验收依据。
 *
 *   curl http://localhost:8080/api/health
 *   => {"code":0,"data":{"status":"UP","time":"..."},"message":"success"}
 */
@RestController
public class HealthController {

    @GetMapping("/api/health")
    public ApiResponse<Map<String, Object>> health() {
        return ApiResponse.success(Map.of(
                "status", "UP",
                "time", Instant.now().toString()
        ));
    }
}
