package com.medication.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 幻觉检测器 - 三重保障机制
 * 1. 来源校验：回答每句话是否可在检索片段找到依据
 * 2. 数字校验：剂量、百分比等数字是否与原文一致
 * 3. 置信度评估：基于回答结构与原文匹配度
 */
@Slf4j
@Component
public class HallucinationDetector {

    private static final Pattern NUMBER_PATTERN = Pattern.compile(
            "\\d+(?:\\.\\d+)?(?:%|mg|g|ml|L|μg|次|片|粒|支|瓶|袋|天|周|月|小时|分钟|岁|岁|kg|cm|mm|℃)?"
    );

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 检测回答是否存在幻觉
     */
    public HallucinationResult detect(String answer, List<String> sourceChunks) {
        List<String> issues = new ArrayList<>();

        // 1. 来源校验
        if (sourceChunks.isEmpty()) {
            issues.add("回答未基于任何检索到的文档片段");
        } else {
            boolean hasSourceMatch = sourceChunks.stream()
                    .anyMatch(chunk -> containsKeywords(answer, extractKeywords(chunk)));
            if (!hasSourceMatch) {
                issues.add("回答内容与检索到的文档片段匹配度较低");
            }
        }

        // 2. 数字校验
        List<String> numbersInAnswer = extractNumbers(answer);
        for (String num : numbersInAnswer) {
            boolean foundInSource = sourceChunks.stream()
                    .anyMatch(chunk -> chunk.contains(num));
            if (!foundInSource) {
                issues.add("数字[" + num + "]未在原文中找到依据");
            }
        }

        // 3. 置信度评估
        double confidence = calculateConfidence(answer, sourceChunks);
        if (confidence < 0.85) {
            issues.add("置信度低于阈值: " + String.format("%.2f", confidence));
        }

        boolean passed = issues.isEmpty();
        log.info("幻觉检测结果: passed={}, confidence={}, issues={}", passed, confidence, issues);
        return new HallucinationResult(passed, confidence, issues);
    }

    /**
     * 提取关键词（排除停用词）
     */
    private List<String> extractKeywords(String text) {
        String[] stopWords = {"的", "了", "是", "在", "和", "与", "或", "及", "等", "可",
                "应", "不", "有", "为", "以", "对", "于", "请", "根据", "说明"};
        List<String> keywords = new ArrayList<>();
        // 提取2-4字词组
        for (int i = 0; i < text.length(); i++) {
            for (int len = 2; len <= 4 && i + len <= text.length(); len++) {
                String word = text.substring(i, i + len);
                if (!containsAny(word, stopWords) && word.trim().length() == len) {
                    keywords.add(word);
                }
            }
        }
        return keywords;
    }

    private boolean containsKeywords(String text, List<String> keywords) {
        if (keywords.isEmpty()) return true;
        long matchCount = keywords.stream().filter(text::contains).count();
        return (double) matchCount / keywords.size() > 0.3;
    }

    private List<String> extractNumbers(String text) {
        List<String> numbers = new ArrayList<>();
        Matcher matcher = NUMBER_PATTERN.matcher(text);
        while (matcher.find()) {
            numbers.add(matcher.group());
        }
        return numbers;
    }

    private double calculateConfidence(String answer, List<String> sources) {
        if (sources.isEmpty()) return 0.0;

        List<String> answerKeywords = extractKeywords(answer);
        if (answerKeywords.isEmpty()) return 0.5;

        long matched = answerKeywords.stream()
                .filter(kw -> sources.stream().anyMatch(s -> s.contains(kw)))
                .count();

        return Math.min(1.0, (double) matched / answerKeywords.size());
    }

    private boolean containsAny(String text, String[] words) {
        for (String w : words) {
            if (text.contains(w)) return true;
        }
        return false;
    }

    @lombok.Data
    @lombok.AllArgsConstructor
    @lombok.NoArgsConstructor
    public static class HallucinationResult {
        private boolean passed;
        private double confidenceScore;
        private List<String> issues;
    }
}