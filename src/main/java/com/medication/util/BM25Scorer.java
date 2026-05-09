package com.medication.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * BM25 scorer for keyword-based document ranking
 */
@Slf4j
@Component
public class BM25Scorer {

    private final Map<String, List<String>> documentCorpus = new ConcurrentHashMap<>();
    private final Map<String, Long> termDocFreq = new ConcurrentHashMap<>();

    private volatile double avgDocLen = 0.0;
    private static final double K1 = 1.5;
    private static final double B = 0.75;

    private static final Set<String> STOP_WORDS = Set.of(
            "的", "了", "是", "在", "和", "与", "或", "及", "等", "可",
            "什么", "怎么", "如何", "吗", "呢", "有", "个", "我",
            "你", "他", "她", "它", "们", "这", "那", "就", "也", "都"
    );

    private static final Pattern WORD_PATTERN = Pattern.compile("[\\u4e00-\\u9fa5a-zA-Z0-9]+");

    /**
     * Update index with a new or updated document
     */
    public void addDocument(String docId, String text) {
        removeDocument(docId);
        List<String> tokens = tokenize(text);
        documentCorpus.put(docId, tokens);

        for (String token : new HashSet<>(tokens)) {
            termDocFreq.merge(token, 1L, Long::sum);
        }

        updateAvgDocLen();
        log.debug("BM25 index updated: docId={}, tokenCount={}", docId, tokens.size());
    }

    /**
     * Remove document from index
     */
    public void removeDocument(String docId) {
        List<String> oldTokens = documentCorpus.remove(docId);
        if (oldTokens != null) {
            Set<String> uniqueTokens = new HashSet<>(oldTokens);
            for (String token : uniqueTokens) {
                termDocFreq.computeIfPresent(token, (k, v) -> v > 1 ? v - 1 : null);
            }
            updateAvgDocLen();
        }
        log.debug("BM25 index: doc removed", docId);
    }

    private void updateAvgDocLen() {
        int docCount = documentCorpus.size();
        avgDocLen = docCount > 0
            ? documentCorpus.values().stream().mapToInt(List::size).average().orElse(0)
            : 0;
    }

    /**
     * Clear all indexed documents
     */
    public void clear() {
        documentCorpus.clear();
        termDocFreq.clear();
        avgDocLen = 0;
        log.info("BM25 index cleared");
    }

    /**
     * Calculate BM25 score for a query against a single document
     */
    public double score(String docId, String query) {
        List<String> docTokens = documentCorpus.get(docId);
        if (docTokens == null || docTokens.isEmpty() || avgDocLen == 0) {
            return 0.0;
        }

        List<String> queryTokens = tokenize(query);
        if (queryTokens.isEmpty()) {
            return 0.0;
        }

        double score = 0.0;
        int docLen = docTokens.size();

        for (String queryToken : queryTokens) {
            Long docFreq = termDocFreq.get(queryToken);
            if (docFreq == null || docFreq == 0) continue;

            double idf = calculateIdf(docFreq);

            long termFreq = Collections.frequency(docTokens, queryToken);

            double numerator = termFreq * (K1 + 1);
            double denominator = termFreq + K1 * (1 - B + B * docLen / avgDocLen);

            score += idf * numerator / denominator;
        }

        return score;
    }

    /**
     * Calculate BM25 scores for multiple documents
     */
    public Map<String, Double> scoreDocuments(String query, List<String> docIds) {
        Map<String, Double> scores = new HashMap<>();
        for (String docId : docIds) {
            scores.put(docId, score(docId, query));
        }
        return scores;
    }

    /**
     * Score all indexed documents against query
     */
    public Map<String, Double> scoreAll(String query) {
        return scoreDocuments(query, new ArrayList<>(documentCorpus.keySet()));
    }

    private double calculateIdf(long docFreq) {
        int n = documentCorpus.size();
        if (n == 0) return 0.0;
        return Math.log((n - docFreq + 0.5) / (docFreq + 0.5) + 1);
    }

    /**
     * Simple Chinese tokenization using sliding window (2-4 chars)
     * Filter out stop words
     */
    private List<String> tokenize(String text) {
        if (text == null || text.isEmpty()) {
            return Collections.emptyList();
        }

        Set<String> tokens = new LinkedHashSet<>();

        for (int i = 0; i < text.length(); i++) {
            for (int len = 2; len <= 4 && i + len <= text.length(); len++) {
                String word = text.substring(i, i + len);
                if (!STOP_WORDS.contains(word) && !isPunctuation(word)) {
                    tokens.add(word);
                }
            }
        }

        return new ArrayList<>(tokens);
    }

    private boolean isPunctuation(String word) {
        return !WORD_PATTERN.matcher(word).matches();
    }

    public int getIndexedDocumentCount() {
        return documentCorpus.size();
    }
}