package com.medication.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QueryResponse {

    private Integer code;
    private QueryData data;
    private String message;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class QueryData {
        private String answer;
        private List<Source> sources;
        private HallucinationCheck hallucinationCheck;
        private Long latencyMs;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Source {
        private String section;
        private Double score;
        private String content;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class HallucinationCheck {
        private Boolean passed;
        private Double confidenceScore;
        private List<String> issues;
    }
}