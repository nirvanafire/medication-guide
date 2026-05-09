package com.medication.dto;

import lombok.Data;
import org.springframework.ai.document.Document;

import java.util.Comparator;

/**
 * Hybrid search result with vector and BM25 scores
 */
@Data
public class HybridSearchResult {
    private Document document;
    private double vectorScore;
    private double bm25Score;
    private int vectorRank;
    private int bm25Rank;
    private double fusedScore;

    public HybridSearchResult(Document document, double vectorScore, double bm25Score) {
        this.document = document;
        this.vectorScore = vectorScore;
        this.bm25Score = bm25Score;
    }

    public String getDocId() {
        return document.getId();
    }

    public String getText() {
        return document.getText();
    }

    public static Comparator<HybridSearchResult> byFusedScoreDescending() {
        return (a, b) -> Double.compare(b.getFusedScore(), a.getFusedScore());
    }
}