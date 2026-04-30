package com.medication.controller;

import com.medication.dto.ApiResponse;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 健康检查与系统信息 API
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class HealthController {

    @Value("${spring.application.name:medication-guide}")
    private String appName;

    @Value("${spring.application.version:1.0.0}")
    private String appVersion;

    @GetMapping("/health")
    public ApiResponse<HealthInfo> health() {
        return ApiResponse.success(new HealthInfo(
                "UP",
                appName,
                appVersion,
                LocalDateTime.now()
        ));
    }

    @GetMapping("/info")
    public ApiResponse<Map<String, Object>> info() {
        Map<String, Object> info = new HashMap<>();
        info.put("name", appName);
        info.put("version", appVersion);
        info.put("description", "药品说明书智能问答系统");
        info.put("architecture", "RAG (Retrieval-Augmented Generation)");
        return ApiResponse.success(info);
    }

    @Data
    @lombok.AllArgsConstructor
    public static class HealthInfo {
        private String status;
        private String name;
        private String version;
        private LocalDateTime timestamp;
    }
}