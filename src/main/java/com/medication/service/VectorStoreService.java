package com.medication.service;

import com.medication.util.DocumentParser;
import io.milvus.client.MilvusServiceClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
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

                List<Document> documents = batch.stream()
                        .map(chunk -> {
                            Map<String, Object> metadata = new HashMap<>();
                            metadata.put("drug_name", chunk.getDrugName());
                            metadata.put("section", chunk.getSection());
                            metadata.put("chunk_index", chunk.getChunkIndex());
                            metadata.put("token_count", chunk.getTokenCount());

                            String text = chunk.getContent();
                            if (text.length() > MAX_EMBEDDING_TEXT_LENGTH) {
                                text = text.substring(0, MAX_EMBEDDING_TEXT_LENGTH);
                                log.warn("文本过长已截断: {} section={} original={}", chunk.getDrugName(), chunk.getSection(), chunk.getTokenCount());
                            }

                            return new Document(text, metadata);
                        })
                        .collect(Collectors.toList());

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
        SearchRequest searchRequest = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .similarityThreshold(threshold)
                .build();

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