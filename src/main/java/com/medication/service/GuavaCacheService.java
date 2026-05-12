package com.medication.service;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.medication.config.CacheConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Guava cache service implementation
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "cache.type", havingValue = "guava")
public class GuavaCacheService implements ICacheService {

    private final CacheConfig cacheConfig;

    private static final String CACHE_PREFIX = "medication:qa:";
    private static final int MAX_SIZE = 10000;

    private final Map<String, CachedAnswer> cacheMap = new ConcurrentHashMap<>();

    private Cache<String, CachedAnswer> getCache() {
        return CacheBuilder.newBuilder()
                .maximumSize(MAX_SIZE)
                .expireAfterWrite(cacheConfig.getTtlHours(), TimeUnit.HOURS)
                .build();
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
            CachedAnswer value = cacheMap.get(key);
            return Optional.ofNullable(value);
        } catch (Exception e) {
            log.warn("Guava cache read failed", e);
        }
        return Optional.empty();
    }

    @Override
    public void put(String key, String answer, String sources, long latencyMs) {
        if (!isEnabled()) {
            return;
        }
        try {
            CachedAnswer cachedAnswer = new CachedAnswer(answer, sources, latencyMs);
            cacheMap.put(key, cachedAnswer);
            log.debug("Cached: {}", key);
        } catch (Exception e) {
            log.warn("Guava cache write failed", e);
        }
    }

    @Override
    public void clearByDrugName(String drugName) {
        if (!isEnabled()) {
            return;
        }
        try {
            String prefix = CACHE_PREFIX + drugName + ":";
            int cleared = 0;
            var keysToRemove = cacheMap.keySet().stream()
                    .filter(key -> key.startsWith(prefix))
                    .toList();
            for (String key : keysToRemove) {
                cacheMap.remove(key);
                cleared++;
            }
            if (cleared > 0) {
                log.info("Cleared cache: {} keys", cleared);
            }
        } catch (Exception e) {
            log.warn("Guava cache clear failed", e);
        }
    }
}
