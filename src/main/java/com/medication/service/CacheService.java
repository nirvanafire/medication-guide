package com.medication.service;

import com.medication.config.CacheConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

/**
 * 缓存服务 - 热点问答缓存
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CacheService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final CacheConfig cacheConfig;

    private static final String CACHE_PREFIX = "medication:qa:";

    private Duration getCacheTtl() {
        return Duration.ofHours(cacheConfig.getTtlHours());
    }

    /**
     * 检查缓存是否启用
     */
    public boolean isEnabled() {
        return cacheConfig.isEnabled();
    }

    /**
     * 生成缓存 key
     */
    public String generateKey(String question, String drugName) {
        String normalizedQuestion = question.toLowerCase()
                .replaceAll("[?？!！。，,、]", "")
                .trim();
        String key = normalizedQuestion;
        if (drugName != null && !drugName.isBlank()) {
            key = drugName + ":" + normalizedQuestion;
        }
        return CACHE_PREFIX + key.hashCode();
    }

    /**
     * 获取缓存的回答
     */
    @SuppressWarnings("unchecked")
    public Optional<CachedAnswer> get(String key) {
        if (!isEnabled()) {
            return Optional.empty();
        }
        try {
            Object value = redisTemplate.opsForValue().get(key);
            if (value instanceof java.util.Map) {
                java.util.Map<String, Object> map = (java.util.Map<String, Object>) value;
                return Optional.of(new CachedAnswer(
                        (String) map.get("answer"),
                        (String) map.get("sources"),
                        (Long) map.get("latencyMs")
                ));
            }
        } catch (Exception e) {
            log.warn("缓存读取失败", e);
        }
        return Optional.empty();
    }

    /**
     * 缓存回答
     */
    public void put(String key, String answer, String sources, long latencyMs) {
        if (!isEnabled()) {
            return;
        }
        try {
            java.util.Map<String, Object> value = new java.util.HashMap<>();
            value.put("answer", answer);
            value.put("sources", sources);
            value.put("latencyMs", latencyMs);
            redisTemplate.opsForValue().set(key, value, getCacheTtl());
            log.debug("已缓存: {}", key);
        } catch (Exception e) {
            log.warn("缓存写入失败", e);
        }
    }

    /**
     * 清除指定药品的缓存
     */
    public void clearByDrugName(String drugName) {
        if (!isEnabled()) {
            return;
        }
        try {
            String pattern = CACHE_PREFIX + drugName + ":*";
            var keys = redisTemplate.keys(pattern);
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
                log.info("已清除缓存: {} keys", keys.size());
            }
        } catch (Exception e) {
            log.warn("缓存清除失败", e);
        }
    }

    @lombok.Data
    @lombok.AllArgsConstructor
    public static class CachedAnswer {
        private String answer;
        private String sources;
        private Long latencyMs;
    }
}