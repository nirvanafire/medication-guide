package com.medication.service;

import com.medication.util.DocumentParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.stereotype.Service;

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

    /**
     * 将解析后的文档块存入向量库
     */
    public void storeChunks(List<DocumentParser.Chunk> chunks) {
        List<Document> documents = chunks.stream()
                .map(chunk -> {
                    Map<String, Object> metadata = new HashMap<>();
                    metadata.put("drug_name", chunk.getDrugName());
                    metadata.put("section", chunk.getSection());
                    metadata.put("chunk_index", chunk.getChunkIndex());
                    metadata.put("token_count", chunk.getTokenCount());

                    return new Document(chunk.getContent(), metadata);
                })
                .collect(Collectors.toList());

        vectorStore.add(documents);
        log.info("成功存入 {} 个文档块到向量库", documents.size());
    }

    /**
     * 根据问题检索相关文档片段
     */
    public List<Document> searchSimilar(String query, String drugName, int topK, double threshold) {
        SearchRequest.Builder builder = SearchRequest.query(query)
                .withTopK(topK)
                .withSimilarityThreshold(threshold);

        if (drugName != null && !drugName.isBlank()) {
            builder.withFilterExpression("drug_name == '" + drugName + "'");
        }

        SearchRequest searchRequest = builder.build();
        List<Document> results = vectorStore.similaritySearch(searchRequest);

        log.info("检索完成: query='{}', topK={}, 返回 {} 条结果", query.substring(0, Math.min(30, query.length())), topK, results.size());
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
        // Note: Milvus supports delete by filter expression
        log.info("请求删除药品 [{}] 的向量数据", drugName);
    }
}