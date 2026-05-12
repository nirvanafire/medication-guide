package com.medication.service;

import java.util.Optional;

public interface ICacheService {

    Optional<CachedAnswer> get(String key);

    void put(String key, String answer, String sources, long latencyMs);

    void clearByDrugName(String drugName);

    boolean isEnabled();

    String generateKey(String question, String drugName);

    @lombok.Data
    @lombok.AllArgsConstructor
    class CachedAnswer {
        private String answer;
        private String sources;
        private Long latencyMs;
    }
}
