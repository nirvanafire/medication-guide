package com.medication.service;

import com.medication.config.CacheConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;

/**
 * Redis cache service implementation
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "cache.type", havingValue = "redis", matchIfMissing = true)
public class RedisCacheService implements ICacheService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final CacheConfig cacheConfig;

    private static final String CACHE_PREFIX = "medication:qa:";

    private Duration getCacheTtl() {
        return Duration.ofHours(cacheConfig.getTtlHours());
    }

    @Override
    public boolean isEnabled() {
        return cacheConfig.isEnabled();
    }

    @Override
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

    @Override
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
            log.warn("Redis cache read failed", e);
        }
        return Optional.empty();
    }

    @Override
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
            log.debug("Cached: {}", key);
        } catch (Exception e) {
            log.warn("Redis cache write failed", e);
        }
    }

    @Override
    public void clearByDrugName(String drugName) {
        if (!isEnabled()) {
            return;
        }
        try {
            String pattern = CACHE_PREFIX + drugName + ":*";
            var keys = redisTemplate.keys(pattern);
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
                log.info("Cleared cache: {} keys", keys.size());
            }
        } catch (Exception e) {
            log.warn("Redis cache clear failed", e);
        }
    }
}
