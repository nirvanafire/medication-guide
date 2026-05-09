package com.medication.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "rag")
public class RagConfig {

    private ChunkConfig chunk = new ChunkConfig();
    private RetrievalConfig retrieval = new RetrievalConfig();
    private PromptConfig prompt = new PromptConfig();

    @Data
    public static class ChunkConfig {
        private int size = 400;
        private int overlap = 50;
    }

    @Data
    public static class RetrievalConfig {
        private int topK = 5;
        private double similarityThreshold = 0.6;
        private boolean rerankEnabled = true;
        private HybridConfig hybrid = new HybridConfig();
    }

    @Data
    public static class HybridConfig {
        private String fusionMethod = "RRF";
        private int rrfK = 20;
        private double vectorWeight = 0.5;
        private double keywordWeight = 0.5;
        private int vectorSearchLimit = 20;
        private int bm25SearchLimit = 50;
    }

    @Data
    public static class PromptConfig {
        private String template;
    }
}