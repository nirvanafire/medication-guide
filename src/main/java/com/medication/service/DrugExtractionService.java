package com.medication.service;

import com.medication.entity.QueryLog;
import com.medication.repository.QueryLogRepository;
import com.medication.service.DrugNameVectorService.DrugMatch;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DrugExtractionService {

    private final DrugNameVectorService drugNameVectorService;
    private final QueryLogRepository queryLogRepository;

    private static final double HIGH_SIMILARITY_THRESHOLD = 0.65;
    private static final double MEDIUM_SIMILARITY_THRESHOLD = 0.45;

    public ExtractionResult extract(String question, String sessionId) {
        if (question == null || question.isBlank()) {
            return ExtractionResult.noDrugName(null, null, MatchConfidence.LOW);
        }

        String extracted = extractByPattern(question);

        if (extracted != null) {
            String normalized = normalize(extracted);
            if (normalized != null) {
                log.debug("精确匹配成功: '{}' -> '{}'", extracted, normalized);
                return ExtractionResult.confirmed(normalized, extracted, MatchConfidence.HIGH, "EXACT");
            }

            List<DrugMatch> candidates = drugNameVectorService.findSimilarDrugNames(extracted, 3);
            if (!candidates.isEmpty() && candidates.get(0).getSimilarity() > MEDIUM_SIMILARITY_THRESHOLD) {
                log.debug("片段匹配成功: '{}' -> candidates={}", extracted, candidates);
                return ExtractionResult.withCandidates(extracted, extracted, candidates, MatchConfidence.MEDIUM);
            }
        }

        List<DrugMatch> fragmentCandidates = drugNameVectorService.findByFragments(question, 5);
        if (!fragmentCandidates.isEmpty() && fragmentCandidates.get(0).getSimilarity() > MEDIUM_SIMILARITY_THRESHOLD) {
            log.debug("片段提取匹配: question='{}' -> best={}", question, fragmentCandidates.get(0));
            return ExtractionResult.withCandidates(
                    fragmentCandidates.get(0).getStandardName(),
                    question,
                    fragmentCandidates,
                    MatchConfidence.MEDIUM
            );
        }

        if (sessionId != null) {
            Optional<String> inherited = inheritFromContext(sessionId);
            if (inherited.isPresent()) {
                log.debug("从上下文继承药品名: {}", inherited.get());
                return ExtractionResult.confirmed(inherited.get(), null, MatchConfidence.HIGH, "INHERITED");
            }
        }

        List<DrugMatch> globalCandidates = drugNameVectorService.findSimilarDrugNames(question, 3);
        if (!globalCandidates.isEmpty() && globalCandidates.get(0).getSimilarity() > MEDIUM_SIMILARITY_THRESHOLD) {
            return ExtractionResult.withCandidates(null, question, globalCandidates, MatchConfidence.LOW);
        }

        log.debug("未找到匹配的药品名: question='{}'", question);
        return ExtractionResult.noDrugName(question, null, MatchConfidence.LOW);
    }

    private String extractByPattern(String question) {
        String[] patterns = {
                "([^\\s]+?)(?:药品|药物|药)",
                "(?:关于|给|给.*?吃)([^\\s]+?)(?:药品|药物|药)",
                "(?:叫|名叫|名称为)([^\\s]+)",
                "(?:这个|那个|该)(?:药品|药物|药)",
                "([^\\s]+?)(?:霉素|西林|酰胺|苷|片|胶囊|口服液|注射液|颗粒|滴眼液|软膏|贴剂)",
                "(?:是)([^\\s]+?)(?:的)?(?:药|药片|药品)",
        };

        for (String patternStr : patterns) {
            Pattern pattern = Pattern.compile(patternStr);
            Matcher matcher = pattern.matcher(question);
            if (matcher.find()) {
                String extracted = matcher.group(1).trim();
                if (extracted.length() >= 2) {
                    return extracted;
                }
            }
        }

        return null;
    }

    private String normalize(String drugName) {
        if (drugName == null || drugName.isBlank()) {
            return null;
        }

        String normalized = drugNameVectorService.getStandardName(drugName);
        if (normalized != null) {
            return normalized;
        }

        List<DrugMatch> matches = drugNameVectorService.findSimilarDrugNames(drugName, 1);
        if (!matches.isEmpty() && matches.get(0).getSimilarity() > HIGH_SIMILARITY_THRESHOLD) {
            return matches.get(0).getStandardName();
        }

        return null;
    }

    private Optional<String> inheritFromContext(String sessionId) {
        List<QueryLog> recentLogs = queryLogRepository.findBySessionIdOrderByCreatedAtDesc(sessionId);
        return recentLogs.stream()
                .filter(log -> log.getDrugName() != null && !log.getDrugName().isBlank())
                .map(QueryLog::getDrugName)
                .findFirst();
    }

    public List<ConversationTurn> getConversationHistory(String sessionId, int limit) {
        if (sessionId == null || sessionId.isBlank()) {
            return new ArrayList<>();
        }
        List<QueryLog> logs = queryLogRepository.findBySessionId(sessionId);
        return logs.stream()
                .sorted((a, b) -> a.getCreatedAt().compareTo(b.getCreatedAt()))
                .limit(limit)
                .map(log -> new ConversationTurn(log.getQuestion(), log.getAnswer(), log.getCreatedAt()))
                .collect(Collectors.toList());
    }

    @lombok.Data
    @lombok.AllArgsConstructor
    public static class ExtractionResult {
        private String normalizedDrugName;
        private String originalInput;
        private List<DrugMatch> candidates;
        private boolean needsConfirmation;
        private MatchConfidence confidence;

        public static ExtractionResult confirmed(String normalized, String original,
                                                  MatchConfidence confidence, String matchType) {
            return new ExtractionResult(normalized, original, null, false, confidence);
        }

        public static ExtractionResult withCandidates(String normalized, String original,
                                                      List<DrugMatch> candidates, MatchConfidence confidence) {
            return new ExtractionResult(normalized, original, candidates, true, confidence);
        }

        public static ExtractionResult noDrugName(String original, String input, MatchConfidence confidence) {
            return new ExtractionResult(null, original, null, true, confidence);
        }
    }

    public enum MatchConfidence {
        HIGH,
        MEDIUM,
        LOW
    }

    @lombok.Data
    @lombok.AllArgsConstructor
    public static class ConversationTurn {
        private String question;
        private String answer;
        private java.time.LocalDateTime timestamp;
    }
}
