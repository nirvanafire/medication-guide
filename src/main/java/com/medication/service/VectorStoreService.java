package com.medication.service;

import com.medication.config.RagConfig;
import com.medication.dto.HybridSearchResult;
import com.medication.util.BM25Scorer;
import com.medication.util.DocumentParser;
import io.milvus.client.MilvusServiceClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 向量检索服务
 * 支持向量存储和相似度检索
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VectorStoreService {

    private final VectorStore vectorStore;
    private final DocumentParser documentParser;
    private final MilvusServiceClient milvusClient;
    private final BM25Scorer bm25Scorer;
    private final RagConfig ragConfig;

    private static final int MAX_EMBEDDING_TEXT_LENGTH = 1000; // bge-large-zh-v1.5 context limit

    /**
     * 将解析后的文档块存入向量库
     */
    public void storeChunks(List<DocumentParser.Chunk> chunks) {
        log.info("开始存入向量库, chunk数量={}", chunks.size());
        try {
            int batchSize = 10;
            for (int i = 0; i < chunks.size(); i += batchSize) {
                int end = Math.min(i + batchSize, chunks.size());
                List<DocumentParser.Chunk> batch = chunks.subList(i, end);

                List<Document> documents = new ArrayList<>();
                for (DocumentParser.Chunk chunk : batch) {
                    Map<String, Object> metadata = new HashMap<>();
                    metadata.put("drug_name", chunk.getDrugName());
                    metadata.put("section", chunk.getSection());
                    metadata.put("chunk_index", chunk.getChunkIndex());
                    metadata.put("token_count", chunk.getTokenCount());

                    // Use stable chunk ID for BM25 index
                    String chunkKey = chunk.getDrugName() + ":" + chunk.getSection() + ":" + chunk.getChunkIndex();
                    metadata.put("chunk_key", chunkKey);

                    // 构建带有章节标题前缀的内容
                    /*String sectionPrefix = chunk.getChunkIndex() == 0
                            ? "【" + chunk.getSection() + "】"
                            : "【" + chunk.getSection() + "】（续）";*/

                    String text = chunk.getContent();
                    if (text.length() > MAX_EMBEDDING_TEXT_LENGTH) {
                        text = text.substring(0, MAX_EMBEDDING_TEXT_LENGTH);
                        log.warn("文本过长已截断: {} section={} original={}", chunk.getDrugName(), chunk.getSection(), chunk.getTokenCount());
                    }

                    Document doc = new Document(text, metadata);
                    documents.add(doc);

                    // Sync to BM25 index using stable chunk key
                    bm25Scorer.addDocument(chunkKey, text);
                }

                log.debug("批次[{}]准备存入 {} 个文档", (i / batchSize + 1), documents.size());
                vectorStore.add(documents);
                log.info("批次[{}/{}]存入向量库成功", (end / batchSize), (chunks.size() / batchSize + 1));
            }
            log.info("成功存入 {} 个文档块到向量库", chunks.size());
        } catch (Exception e) {
            log.error("存入向量库失败", e);
            throw e;
        }
    }

    /**
     * 检查向量库连接状态
     */
    public boolean checkConnection() {
        try {
            milvusClient.describeCollection(
                    io.milvus.param.collection.DescribeCollectionParam.newBuilder()
                            .withCollectionName("drug_documents")
                            .build());
            return true;
        } catch (Exception e) {
            log.warn("向量库连接检查失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 根据问题检索相关文档片段
     */
    public List<Document> searchSimilar(String query, String drugName, int topK, double threshold) {
        // 使用 Spring AI VectorStore 的默认搜索
        SearchRequest searchRequest = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .similarityThreshold(threshold)
                .build();

        List<Document> results = vectorStore.similaritySearch(searchRequest);

        // 后置过滤：按 drugName 过滤结果
        if (drugName != null && !drugName.isBlank()) {
            final String targetDrugName = drugName;
            results = results.stream()
                    .filter(doc -> {
                        String docDrugName = (String) doc.getMetadata().get("drug_name");
                        return targetDrugName.equals(docDrugName);
                    })
                    .collect(Collectors.toList());
            log.debug("按drugName过滤后剩余 {} 条结果", results.size());
        }

        log.info("检索完成: query='{}', drugName='{}', topK={}, 返回 {} 条结果",
                query.substring(0, Math.min(30, query.length())), drugName, topK, results.size());
        return results;
    }

    /**
     * 混合检索：向量检索 + 关键词匹配
     */
    public List<Document> hybridSearch(String query, String drugName, int topK, double threshold) {
        // 1. 向量语义检索
        List<Document> vectorResults = searchSimilar(query, drugName, topK * 2, threshold * 0.8);

        // 2. 简单的关键词过滤 (BM25可用ElasticSearch增强)
        List<Document> filtered = vectorResults.stream()
                .filter(doc -> containsKeywords(doc.getText(), extractSearchKeywords(query)))
                .collect(Collectors.toList());

        // 3. 如果关键词过滤后结果太少，补充向量检索结果
        if (filtered.size() < topK) {
            filtered.addAll(
                    vectorResults.stream()
                            .filter(doc -> !filtered.contains(doc))
                            .limit(topK - filtered.size())
                            .collect(Collectors.toList())
            );
        }

        return filtered.stream().limit(topK).collect(Collectors.toList());
    }

    private boolean containsKeywords(String text, List<String> keywords) {
        if (keywords.isEmpty()) return true;
        return keywords.stream().anyMatch(text::contains);
    }

    private List<String> extractSearchKeywords(String query) {
        String[] stopWords = {"的", "了", "是", "在", "和", "与", "或", "及", "等", "可", "什么", "怎么", "如何", "吗", "呢"};
        List<String> keywords = new ArrayList<>();
        // 提取2-4字关键词
        for (int i = 0; i < query.length(); i++) {
            for (int len = 2; len <= 4 && i + len <= query.length(); len++) {
                String word = query.substring(i, i + len);
                if (!List.of(stopWords).contains(word)) {
                    keywords.add(word);
                }
            }
        }
        return keywords;
    }

    /**
     * 删除指定药品的向量数据
     */
    public void deleteByDrugName(String drugName) {
        try {
            // Delete by metadata filter
            milvusClient.delete(
                    io.milvus.param.dml.DeleteParam.newBuilder()
                            .withCollectionName("drug_documents")
                            .withExpr("drug_name == \"" + drugName + "\"")
                            .build()
            );
            log.info("已删除药品 [{}] 的向量数据", drugName);
        } catch (Exception e) {
            log.error("删除向量数据失败: drugName={}", drugName, e);
        }
    }

    /**
     * 删除所有向量数据
     */
    public void deleteAll() {
        try {
            milvusClient.delete(
                    io.milvus.param.dml.DeleteParam.newBuilder()
                            .withCollectionName("drug_documents")
                            .withExpr("true")
                            .build()
            );
            log.info("已删除所有向量数据");
        } catch (Exception e) {
            log.error("删除所有向量数据失败", e);
        }
    }

    /**
     * Enhanced hybrid search with RRF fusion
     */
    public List<HybridSearchResult> hybridSearchEnhanced(String query, String drugName,
                                                          int topK, RagConfig.HybridConfig config) {
        // 1. Vector semantic search
        int vectorLimit = config.getVectorSearchLimit();
        List<Document> vectorResults = searchSimilar(query, drugName, vectorLimit, 0);

        if (vectorResults.isEmpty()) {
            log.warn("Vector search returned no results for query: {}", query);
            return new ArrayList<>();
        }

        log.info("Vector search returned {} results", vectorResults.size());

        // 2. Calculate BM25 scores for vector results using chunk_key from metadata
        Map<String, Double> bm25Scores = new HashMap<>();
        for (Document doc : vectorResults) {
            String chunkKey = (String) doc.getMetadata().get("chunk_key");
            if (chunkKey == null) {
                chunkKey = doc.getId();
            }
            double score = bm25Scorer.score(chunkKey, query);
            bm25Scores.put(chunkKey, score);

            // Debug logging for retrieved docs
            String section = (String) doc.getMetadata().getOrDefault("section", "unknown");
            log.debug("Retrieved doc: section={}, chunkKey={}, vectorScore={}, bm25Score={}",
                    section, chunkKey, extractVectorScore(doc), score);
        }

        log.info("BM25 scores calculated, max={}, min={}",
                bm25Scores.values().stream().mapToDouble(Double::doubleValue).summaryStatistics().getMax(),
                bm25Scores.values().stream().mapToDouble(Double::doubleValue).summaryStatistics().getMin());

        // 3. Build hybrid results with initial vector scores
        List<HybridSearchResult> hybridResults = new ArrayList<>();
        for (Document doc : vectorResults) {
            String chunkKey = (String) doc.getMetadata().get("chunk_key");
            if (chunkKey == null) {
                chunkKey = doc.getId();
            }
            hybridResults.add(new HybridSearchResult(
                    doc,
                    extractVectorScore(doc),
                    bm25Scores.getOrDefault(chunkKey, 0.0)
            ));
        }

        // 4. Assign ranks (vector score and BM25 score)
        assignRanks(hybridResults, HybridSearchResult::getVectorScore, HybridSearchResult::setVectorRank);
        assignRanks(hybridResults, HybridSearchResult::getBm25Score, HybridSearchResult::setBm25Rank);

        // 6. Apply RRF fusion
        int rrfK = config.getRrfK();
        for (HybridSearchResult result : hybridResults) {
            double rrfScore = 1.0 / (rrfK + result.getVectorRank())
                            + 1.0 / (rrfK + result.getBm25Rank());
            result.setFusedScore(rrfScore);
        }

        // 7. Sort by fused score and return topK
        hybridResults.sort(HybridSearchResult.byFusedScoreDescending());

        List<HybridSearchResult> topResults = hybridResults.stream()
                .limit(topK)
                .collect(Collectors.toList());

        log.info("Hybrid search completed: {} results, top score={}",
                topResults.size(),
                topResults.isEmpty() ? 0 : topResults.get(0).getFusedScore());

        // Log top results with sections for debugging
        if (!topResults.isEmpty()) {
            String topSections = topResults.stream()
                    .limit(3)
                    .map(r -> {
                        String section = (String) r.getDocument().getMetadata().getOrDefault("section", "unknown");
                        return section + "(" + String.format("%.3f", r.getFusedScore()) + ")";
                    })
                    .collect(Collectors.joining(", "));
            log.info("Top sections: {}", topSections);
        }

        return topResults;
    }

    private double extractVectorScore(Document doc) {
        Object score = doc.getMetadata().get("score");
        if (score instanceof Double) {
            return (Double) score;
        }
        return 0.0;
    }

    private void assignRanks(List<HybridSearchResult> results,
                             java.util.function.ToDoubleFunction<HybridSearchResult> scoreExtractor,
                             java.util.function.BiConsumer<HybridSearchResult, Integer> rankSetter) {
        List<HybridSearchResult> sorted = results.stream()
                .sorted(Comparator.comparingDouble(scoreExtractor).reversed())
                .collect(Collectors.toList());
        for (int i = 0; i < sorted.size(); i++) {
            rankSetter.accept(sorted.get(i), i + 1);
        }
    }
}