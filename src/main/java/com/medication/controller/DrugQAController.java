package com.medication.controller;

import com.medication.dto.*;
import com.medication.entity.QueryLog;
import com.medication.repository.QueryLogRepository;
import com.medication.service.CacheService;
import com.medication.service.RagService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 药品问答 API
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/drug-qa")
@RequiredArgsConstructor
public class DrugQAController {

    private final RagService ragService;
    private final CacheService cacheService;
    private final QueryLogRepository queryLogRepository;

    /**
     * 药品问答接口
     */
    @PostMapping("/query")
    public QueryResponse query(@Valid @RequestBody QueryRequest request) {
        long startTime = System.currentTimeMillis();
        String cacheKey = cacheService.generateKey(request.getQuestion(), request.getDrugName());

        // 检查缓存
        var cached = cacheService.get(cacheKey);
        if (cached.isPresent()) {
            QueryResponse response = QueryResponse.builder()
                    .code(0)
                    .data(QueryResponse.QueryData.builder()
                            .answer(cached.get().getAnswer())
                            .latencyMs(cached.get().getLatencyMs())
                            .build())
                    .message("cache hit")
                    .build();

            logQuery(request, cached.get().getAnswer(), null, System.currentTimeMillis() - startTime, true);
            return response;
        }

        // RAG 检索增强
        int topK = request.getTopK() != null ? request.getTopK() : 3;
        RagService.RagResult result = ragService.query(request.getQuestion(), request.getDrugName(), topK);

        // 构建响应
        List<QueryResponse.Source> sources = result.getSources().stream()
                .map(doc -> QueryResponse.Source.builder()
                        .section((String) doc.getMetadata().getOrDefault("section", "未知"))
                        .score((Double) doc.getMetadata().getOrDefault("score", 0.0))
                        .content(doc.getText().substring(0, Math.min(100, doc.getText().length())))
                        .build())
                .collect(Collectors.toList());

        QueryResponse.HallucinationCheck check = null;
        if (result.getHallucinationCheck() != null) {
            check = QueryResponse.HallucinationCheck.builder()
                    .passed(result.getHallucinationCheck().isPassed())
                    .confidenceScore(result.getHallucinationCheck().getConfidenceScore())
                    .issues(result.getHallucinationCheck().getIssues())
                    .build();
        }

        QueryResponse response = QueryResponse.builder()
                .code(0)
                .data(QueryResponse.QueryData.builder()
                        .answer(result.getAnswer())
                        .sources(sources)
                        .hallucinationCheck(check)
                        .latencyMs(result.getLatencyMs())
                        .build())
                .build();

        // 写入缓存
        cacheService.put(cacheKey, result.getAnswer(), sources.toString(), result.getLatencyMs());

        // 记录日志
        logQuery(request, result.getAnswer(), check, System.currentTimeMillis() - startTime, false);

        return response;
    }

    /**
     * 流式问答接口 (SSE)
     */
    @PostMapping(value = "/query/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> queryStream(@Valid @RequestBody QueryRequest request) {
        int topK = request.getTopK() != null ? request.getTopK() : 3;
        RagService.RagResult result = ragService.query(request.getQuestion(), request.getDrugName(), topK);

        return Flux.fromStream(result.getAnswer().chars()
                .mapToObj(c -> String.valueOf((char) c)));
    }

    /**
     * 健康检查
     */
    @GetMapping("/health")
    public QueryResponse health() {
        return QueryResponse.builder()
                .code(0)
                .data(QueryResponse.QueryData.builder()
                        .answer("OK")
                        .build())
                .message("service healthy")
                .build();
    }

    private void logQuery(QueryRequest request, String answer,
                          QueryResponse.HallucinationCheck check,
                          long latencyMs, boolean cacheHit) {
        QueryLog log = new QueryLog();
        log.setQuestion(request.getQuestion());
        log.setDrugName(request.getDrugName());
        log.setAnswer(answer);
        log.setHallucinationPassed(check != null ? check.getPassed() : null);
        log.setConfidenceScore(check != null ? check.getConfidenceScore() : null);
        log.setLatencyMs((int) latencyMs);
        log.setCacheHit(cacheHit);
        log.setSessionId(request.getSessionId());
        log.setUserId(request.getUserId());
        log.setCreatedAt(LocalDateTime.now());
        queryLogRepository.save(log);
    }
}