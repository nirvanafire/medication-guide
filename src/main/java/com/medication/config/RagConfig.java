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
    }

    @Data
    public static class PromptConfig {
        private String template;
    }
}