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
import java.util.LinkedHashMap;
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

    // 内存缓存：chunk_key -> Document，用于 BM25 快速检索
    private final Map<String, Document> chunkKeyCache = new HashMap<>();

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

                    // 构建带有章节标题前缀的文本，提升向量检索相关性
                    String sectionPrefix = chunk.getChunkIndex() == 0
                            ? "【" + chunk.getSection() + "】"
                            : "【" + chunk.getSection() + "】（续）";
                    String text = sectionPrefix + chunk.getContent();
                    if (text.length() > MAX_EMBEDDING_TEXT_LENGTH) {
                        text = text.substring(0, MAX_EMBEDDING_TEXT_LENGTH);
                        log.warn("文本过长已截断: {} section={} original={}", chunk.getDrugName(), chunk.getSection(), chunk.getTokenCount());
                    }

                    Document doc = new Document(text, metadata);
                    documents.add(doc);

                    // Sync to BM25 index using stable chunk key
                    bm25Scorer.addDocument(chunkKey, text);

                    // 缓存 Document 用于 BM25 快速检索
                    chunkKeyCache.put(chunkKey, doc);
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
            // 清理缓存
            clearCacheByDrugName(drugName);
            // 从 BM25 索引中移除
            bm25Scorer.removeByDrugName(drugName);
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
            // 清空缓存
            clearCache();
            // 清空 BM25 索引
            bm25Scorer.clear();
            log.info("已删除所有向量数据");
        } catch (Exception e) {
            log.error("删除所有向量数据失败", e);
        }
    }

    /**
     * BM25 关键词独立检索
     * 通过 BM25 分数获取 top N 匹配的文档（使用内存缓存）
     */
    public List<Document> bm25Search(String query, String drugName, int limit) {
        // 1. 获取 BM25 分数最高的 docIds
        List<String> topDocIds = bm25Scorer.getTopDocIds(query, limit);

        if (topDocIds.isEmpty()) {
            log.debug("BM25 检索无结果: query={}", query);
            return new ArrayList<>();
        }

        log.debug("BM25 top docIds: {} 个, query={}", topDocIds.size(), query);

        // 2. 从缓存中获取 Document 并过滤 drugName
        List<Document> results = new ArrayList<>();
        for (String chunkKey : topDocIds) {
            Document doc = chunkKeyCache.get(chunkKey);
            if (doc != null) {
                String docDrugName = (String) doc.getMetadata().get("drug_name");
                if (drugName == null || drugName.isBlank() || drugName.equals(docDrugName)) {
                    results.add(doc);
                    if (results.size() >= limit) break;
                }
            }
        }

        log.info("BM25 检索完成: query='{}', drugName='{}', 获取 {} 个文档",
                query.substring(0, Math.min(20, query.length())), drugName, results.size());

        return results;
    }

    /**
     * 清理缓存中的指定药品数据
     */
    public void clearCacheByDrugName(String drugName) {
        chunkKeyCache.entrySet().removeIf(entry -> {
            String entryDrugName = (String) entry.getValue().getMetadata().get("drug_name");
            return drugName.equals(entryDrugName);
        });
        log.info("已清理缓存中药品 [{}] 的数据", drugName);
    }

    /**
     * 清空所有缓存
     */
    public void clearCache() {
        chunkKeyCache.clear();
        log.info("已清空所有缓存");
    }

    /**
     * Enhanced hybrid search with dual-channel retrieval and weighted RRF fusion
     * 双通道独立检索 + 加权 RRF 融合
     */
    public List<HybridSearchResult> hybridSearchEnhanced(String query, String drugName,
                                                          int topK, RagConfig.HybridConfig config) {
        // 获取配置参数
        int vectorLimit = config.getVectorSearchLimit();
        int bm25Limit = config.getBm25SearchLimit();
        double similarityThreshold = ragConfig.getRetrieval().getSimilarityThreshold();
        double vectorWeight = config.getVectorWeight();
        double keywordWeight = config.getKeywordWeight();

        // 1. 向量语义检索通道
        List<Document> vectorResults = searchSimilar(query, drugName, vectorLimit, similarityThreshold);
        log.info("向量检索: query='{}', drugName='{}', 获取 {} 条结果, threshold={}",
                query.substring(0, Math.min(20, query.length())), drugName, vectorResults.size(), similarityThreshold);

        // 2. BM25 关键词独立检索通道
        List<Document> bm25Results = bm25Search(query, drugName, bm25Limit);
        log.info("BM25检索: query='{}', drugName='{}', 获取 {} 条结果",
                query.substring(0, Math.min(20, query.length())), drugName, bm25Results.size());

        // 如果两个通道都无结果，返回空
        if (vectorResults.isEmpty() && bm25Results.isEmpty()) {
            log.warn("双通道检索均无结果: query={}", query);
            return new ArrayList<>();
        }

        // 3. 合并结果集（去重）
        Map<String, HybridSearchResult> resultMap = new LinkedHashMap<>();

        // 添加向量结果
        for (Document doc : vectorResults) {
            String chunkKey = getChunkKey(doc);
            double vectorScore = extractVectorScore(doc);
            resultMap.put(chunkKey, new HybridSearchResult(doc, vectorScore, 0.0));
        }

        // 补充 BM25 结果（只添加不在向量结果中的）
        for (Document doc : bm25Results) {
            String chunkKey = getChunkKey(doc);
            if (!resultMap.containsKey(chunkKey)) {
                // 从缓存获取 BM25 分数
                double bm25Score = bm25Scorer.score(chunkKey, query);
                resultMap.put(chunkKey, new HybridSearchResult(doc, 0.0, bm25Score));
            }
        }

        List<HybridSearchResult> hybridResults = new ArrayList<>(resultMap.values());

        log.info("合并后结果数: {} (向量:{} + BM25:{})",
                hybridResults.size(), vectorResults.size(), bm25Results.size());

        // 4. 分配排名（按各自的分数）
        assignRanks(hybridResults, HybridSearchResult::getVectorScore, HybridSearchResult::setVectorRank);
        assignRanks(hybridResults, HybridSearchResult::getBm25Score, HybridSearchResult::setBm25Rank);

        // 5. 加权 RRF 融合
        int rrfK = config.getRrfK();
        for (HybridSearchResult result : hybridResults) {
            double fusedScore = (vectorWeight / (rrfK + result.getVectorRank()))
                              + (keywordWeight / (rrfK + result.getBm25Rank()));
            result.setFusedScore(fusedScore);

            String section = (String) result.getDocument().getMetadata().getOrDefault("section", "unknown");
            log.debug("融合结果: section={}, vectorScore={}, bm25Score={}, vectorRank={}, bm25Rank={}, fusedScore={}",
                    section, result.getVectorScore(), result.getBm25Score(),
                    result.getVectorRank(), result.getBm25Rank(), fusedScore);
        }

        // 6. 按融合分数排序并返回 topK
        hybridResults.sort(HybridSearchResult.byFusedScoreDescending());

        List<HybridSearchResult> topResults = hybridResults.stream()
                .limit(topK)
                .collect(Collectors.toList());

        log.info("混合检索完成: topK={}, 融合分数最高的章节: {}",
                topResults.size(),
                topResults.isEmpty() ? "无" : topResults.get(0).getDocument().getMetadata().get("section"));

        // Fallback: 混合检索无结果时，尝试按 section 名称匹配
        if (topResults.isEmpty() && query.length() <= 10) {
            log.info("混合检索无结果，尝试按 section 名称精确匹配: query={}", query);
            topResults = searchBySectionName(query, drugName, topK);
        }

        // 输出 top3 结果用于调试
        if (!topResults.isEmpty()) {
            String topSections = topResults.stream()
                    .limit(3)
                    .map(r -> {
                        String section = (String) r.getDocument().getMetadata().getOrDefault("section", "unknown");
                        return section + "(vec:" + String.format("%.2f", r.getVectorScore())
                                + "/bm25:" + String.format("%.2f", r.getBm25Score())
                                + "/fused:" + String.format("%.3f", r.getFusedScore()) + ")";
                    })
                    .collect(Collectors.joining(", "));
            log.info("Top3结果: {}", topSections);
        }

        return topResults;
    }

    /**
     * Fallback: 按 section 名称精确匹配
     */
    private List<HybridSearchResult> searchBySectionName(String sectionName, String drugName, int topK) {
        List<HybridSearchResult> results = new ArrayList<>();

        for (Document doc : chunkKeyCache.values()) {
            String docDrugName = (String) doc.getMetadata().get("drug_name");
            String docSection = (String) doc.getMetadata().get("section");

            // 匹配药品名和章节名
            boolean drugMatch = drugName == null || drugName.isBlank() || drugName.equals(docDrugName);
            boolean sectionMatch = docSection != null && docSection.contains(sectionName);

            if (drugMatch && sectionMatch) {
                String chunkKey = getChunkKey(doc);
                double bm25Score = bm25Scorer.score(chunkKey, sectionName);
                results.add(new HybridSearchResult(doc, 0.5, bm25Score));
            }

            if (results.size() >= topK * 2) break;
        }

        if (results.isEmpty()) {
            log.warn("Section fallback 也无结果: section={}", sectionName);
            return results;
        }

        // 分配排名并融合
        assignRanks(results, HybridSearchResult::getVectorScore, HybridSearchResult::setVectorRank);
        assignRanks(results, HybridSearchResult::getBm25Score, HybridSearchResult::setBm25Rank);

        int rrfK = 20;
        double vectorWeight = 0.4;
        double keywordWeight = 0.6;
        for (HybridSearchResult r : results) {
            r.setFusedScore(vectorWeight / (rrfK + r.getVectorRank())
                           + keywordWeight / (rrfK + r.getBm25Rank()));
        }

        results.sort(HybridSearchResult.byFusedScoreDescending());
        return results.stream().limit(topK).collect(Collectors.toList());
    }

    private String getChunkKey(Document doc) {
        String chunkKey = (String) doc.getMetadata().get("chunk_key");
        return chunkKey != null ? chunkKey : doc.getId();
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