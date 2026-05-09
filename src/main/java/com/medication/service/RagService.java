package com.medication.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.medication.config.RagConfig;
import com.medication.dto.HybridSearchResult;
import com.medication.util.HallucinationDetector.HallucinationResult;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * RAG 检索增强生成服务
 * 核心流程：检索 → 构建Prompt → LLM生成 → 答案后处理
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagService {

    private final OllamaApi ollamaApi;
    private final VectorStoreService vectorStoreService;
    private final HallucinationService hallucinationService;
    private final RagConfig ragConfig;

    @Value("${spring.ai.ollama.chat.model:qwen3.5:9b}")
    private String model;

    /**
     * 处理用户问题，返回基于药品说明书的回答
     */
    public RagResult query(String question, String drugName, int topK) {
        long startTime = System.currentTimeMillis();

        // 1. 查询重写（将口语化问题改写为检索友好的形式）
        String rewrittenQuery = rewriteQuery(question, drugName);
        log.debug("查询重写: '{}' -> '{}'", question, rewrittenQuery);

        // 2. Enhanced hybrid search with RRF fusion
        RagConfig.HybridConfig hybridConfig = ragConfig.getRetrieval().getHybrid();
        List<HybridSearchResult> hybridResults = vectorStoreService.hybridSearchEnhanced(
                rewrittenQuery, drugName, topK, hybridConfig
        );

        if (hybridResults.isEmpty()) {
            return RagResult.noData("根据药品说明书，未找到相关信息");
        }

        // 3. Extract documents for further processing
        List<Document> relevantDocs = hybridResults.stream()
                .map(HybridSearchResult::getDocument)
                .collect(Collectors.toList());

        // 4. Build context
        String context = buildContext(relevantDocs);

        // 5. 构建 Prompt 并调用 LLM
        String prompt = buildPrompt(context, question);
        String answer = callLlm(prompt);

        // 6. 答案后处理
        answer = postProcess(answer);

        // 7. 幻觉检测
        List<String> sourceContents = relevantDocs.stream()
                .map(Document::getText)
                .collect(Collectors.toList());
        var hallucinationCheck = hallucinationService.check(answer, sourceContents);

        long latency = System.currentTimeMillis() - startTime;

        log.info("Query completed: latency={}ms, sources={}", latency, relevantDocs.size());

        return RagResult.success(answer, relevantDocs, hallucinationCheck, latency);
    }

    /**
     * 查询重写：将口语化问题转为检索友好形式
     */
    private String rewriteQuery(String question, String drugName) {
        StringBuilder rewritten = new StringBuilder();

        if (drugName != null && !drugName.isBlank()) {
            rewritten.append(drugName);
        }

        // 去掉口语化成分
        String cleaned = question
                .replaceAll("[?？!！。，,、]", " ")
                .replaceAll("什么|怎么|如何|多少|哪些|哪个|是不是|有没有|能不能|可以|应该|需要", "")
                .trim();

        if (rewritten.length() > 0) {
            rewritten.append(" ").append(cleaned);
        } else {
            rewritten.append(cleaned);
        }

        return rewritten.toString();
    }

    /**
     * 简单重排序（基于关键词匹配度）
     * 生产环境可用 Cross-Encoder 精排
     */
    private List<Document> rerank(List<Document> docs, String query) {
        if (!ragConfig.getRetrieval().isRerankEnabled()) {
            return docs;
        }

        return docs.stream()
                .sorted((a, b) -> {
                    double scoreA = computeRelevanceScore(a.getText(), query);
                    double scoreB = computeRelevanceScore(b.getText(), query);
                    return Double.compare(scoreB, scoreA);
                })
                .collect(Collectors.toList());
    }

    private double computeRelevanceScore(String text, String query) {
        String[] queryTerms = query.split("\\s+");
        long matchCount = List.of(queryTerms).stream()
                .filter(term -> !term.isBlank())
                .filter(text::contains)
                .count();
        return (double) matchCount / Math.max(1, queryTerms.length);
    }

    private static final int MAX_CONTEXT_LENGTH = 3000;

    private String buildContext(List<Document> docs) {
        StringBuilder context = new StringBuilder();
        for (int i = 0; i < docs.size(); i++) {
            Document doc = docs.get(i);
            String section = (String) doc.getMetadata().getOrDefault("section", "未知章节");
            context.append("【").append(section).append("】\n");
            context.append(doc.getText()).append("\n\n");

            if (context.length() >= MAX_CONTEXT_LENGTH) {
                context.setLength(MAX_CONTEXT_LENGTH);
                break;
            }
        }
        return context.toString();
    }

    private String buildPrompt(String context, String question) {
        String template = ragConfig.getPrompt().getTemplate();
        if (template != null && !template.isBlank()) {
            PromptTemplate promptTemplate = new PromptTemplate(template);
            Map<String, Object> params = new HashMap<>();
            params.put("context", context);
            params.put("question", question);
            return promptTemplate.render(params);
        }

        // Default prompt
        return """
                你是一位专业的药品说明书解读助手。
                
                【绝对规则】
                1. 你只能根据提供的【药品说明书片段】回答问题
                2. 如果片段中没有相关信息，回答"根据药品说明书，未找到相关信息"
                3. 严禁自行编造、推断任何未在文档中出现的内容
                4. 严禁使用预训练知识回答问题
                5. 必须标注信息来源章节
                
                【药品说明书片段】
                %s
                
                【用户问题】
                %s
                """.formatted(context, question);
    }

    private String callLlm(String prompt) {
        log.debug("开始调用LLM, model={}, prompt长度={}", model, prompt.length());
        long start = System.currentTimeMillis();
        try {
            Map<String, Object> options = Map.of(
                    "num_predict", 1024,
                    "num_ctx", 4096,
                    "temperature", 0.1
            );

            OllamaApi.ChatRequest chatRequest = OllamaApi.ChatRequest.builder(model)
                    .messages(List.of(new OllamaApi.Message(
                            OllamaApi.Message.Role.USER, prompt, null, null)))
                    .stream(false)
                    .options(options)
                    .build();

            OllamaApi.ChatResponse response = ollamaApi.chat(chatRequest);
            long elapsed = System.currentTimeMillis() - start;
            log.debug("LLM调用完成, 耗时={}ms, eval_count={}", elapsed, response.evalCount());
            return response.message().content();
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            log.error("LLM调用失败, 耗时={}ms", elapsed, e);
            return "系统暂时无法回答，请稍后再试。";
        }
    }

    private String postProcess(String answer) {
        if (answer == null) return "";

        // 清理多余空白
        answer = answer.trim().replaceAll("\\n{3,}", "\n\n");

        // 添加免责声明
        if (!answer.contains("免责") && !answer.contains("声明") && !answer.contains("未找到")) {
            answer += "\n\n---\n⚠️ 以上回答基于药品说明书内容，仅供参考。具体用药请遵医嘱。";
        }

        return answer;
    }

    @lombok.Data
    @lombok.AllArgsConstructor(staticName = "of")
    public static class RagResult {
        private String answer;
        private List<Document> sources;
        private HallucinationResult hallucinationCheck;
        private Long latencyMs;
        private boolean success;

        public static RagResult noData(String message) {
            return RagResult.of(message, List.of(), null, 0L, false);
        }

        public static RagResult success(String answer, List<Document> sources,
                                         HallucinationResult check, long latency) {
            return RagResult.of(answer, sources, check, latency, true);
        }
    }
}