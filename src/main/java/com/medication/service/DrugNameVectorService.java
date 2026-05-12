package com.medication.service;

import com.medication.entity.DrugAlias;
import com.medication.repository.DrugAliasRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DrugNameVectorService {

    private final DrugAliasRepository aliasRepository;

    private final Map<String, String> aliasToStandard = new ConcurrentHashMap<>();
    private final Map<String, Double> drugPopularity = new ConcurrentHashMap<>();
    private Set<String> allStandardNames = new HashSet<>();

    private static final double HIGH_SIMILARITY_THRESHOLD = 0.6;
    private static final double MEDIUM_SIMILARITY_THRESHOLD = 0.4;

    @PostConstruct
    public void initialize() {
        log.info("初始化药品名匹配服务...");
        try {
            loadAllDrugNames();
            log.info("药品名匹配服务初始化完成，共 {} 个标准药品名, {} 个别名映射",
                    allStandardNames.size(), aliasToStandard.size());
        } catch (Exception e) {
            log.error("药品名匹配服务初始化失败", e);
        }
    }

    private void loadAllDrugNames() {
        List<DrugAlias> allAliases = aliasRepository.findAll();

        for (DrugAlias alias : allAliases) {
            aliasToStandard.put(alias.getAliasName(), alias.getStandardName());
            String key = alias.getStandardName();
            double popularity = drugPopularity.getOrDefault(key, 0.0) + alias.getPriority();
            drugPopularity.put(key, popularity);
        }

        allStandardNames = aliasRepository.findAllStandardNames();
    }

    public List<DrugMatch> findSimilarDrugNames(String input, int topK) {
        List<DrugMatch> results = new ArrayList<>();

        for (String standardName : allStandardNames) {
            double score = calculateSimilarity(input, standardName);
            if (score > MEDIUM_SIMILARITY_THRESHOLD) {
                results.add(new DrugMatch(
                        standardName,
                        standardName,
                        score,
                        score > HIGH_SIMILARITY_THRESHOLD ? "HIGH_SIMILARITY" : "MEDIUM_SIMILARITY"
                ));
            }
        }

        for (Map.Entry<String, String> entry : aliasToStandard.entrySet()) {
            if (!entry.getKey().equals(entry.getValue())) {
                double score = calculateSimilarity(input, entry.getKey());
                if (score > 0.5) {
                    results.add(new DrugMatch(
                            entry.getValue(),
                            entry.getKey(),
                            score * 0.8,
                            "ALIAS_MATCH"
                    ));
                }
            }
        }

        results.sort((a, b) -> {
            int cmp = Double.compare(b.getSimilarity(), a.getSimilarity());
            if (cmp != 0) return cmp;
            return Double.compare(
                    drugPopularity.getOrDefault(b.getStandardName(), 0.0),
                    drugPopularity.getOrDefault(a.getStandardName(), 0.0)
            );
        });

        return results.stream().limit(topK).collect(Collectors.toList());
    }

    public List<DrugMatch> findByFragments(String question, int topK) {
        List<String> fragments = extractFragments(question);
        List<DrugMatch> allMatches = new ArrayList<>();

        log.debug("提取到的片段: {}", fragments);

        for (String fragment : fragments) {
            if (fragment.length() < 2) continue;
            List<DrugMatch> matches = findSimilarDrugNames(fragment, topK);
            for (DrugMatch match : matches) {
                match.setSimilarity(match.getSimilarity() * getFragmentBoost(fragment, match));
                match.setMatchType("FRAGMENT_" + match.getMatchType());
            }
            allMatches.addAll(matches);
        }

        Map<String, DrugMatch> bestMatchMap = new LinkedHashMap<>();
        for (DrugMatch match : allMatches) {
            String key = match.getStandardName();
            if (!bestMatchMap.containsKey(key) || bestMatchMap.get(key).getSimilarity() < match.getSimilarity()) {
                bestMatchMap.put(key, match);
            }
        }

        List<DrugMatch> results = new ArrayList<>(bestMatchMap.values());
        results.sort((a, b) -> {
            int cmp = Double.compare(b.getSimilarity(), a.getSimilarity());
            if (cmp != 0) return cmp;
            return Double.compare(
                    drugPopularity.getOrDefault(b.getStandardName(), 0.0),
                    drugPopularity.getOrDefault(a.getStandardName(), 0.0)
            );
        });

        log.debug("片段匹配结果: {}", results);
        return results.stream().limit(topK).collect(Collectors.toList());
    }

    private double getFragmentBoost(String fragment, DrugMatch match) {
        double boost = 1.0;
        String matched = match.getMatchedAlias();

        if (fragment.contains(matched) || matched.contains(fragment)) {
            boost *= 1.2;
        }

        if (fragment.length() >= 4 && matched.startsWith(fragment.substring(0, Math.min(2, fragment.length())))) {
            boost *= 1.1;
        }

        return boost;
    }

    private List<String> extractFragments(String text) {
        List<String> fragments = new ArrayList<>();

        fragments.addAll(extractDrugRelatedFragments(text));

        fragments.addAll(extractNGrams(text, 3, 6));

        return fragments.stream()
                .filter(f -> f.length() >= 2)
                .filter(f -> !isStopFragment(f))
                .distinct()
                .sorted((a, b) -> b.length() - a.length())
                .collect(Collectors.toList());
    }

    private boolean isStopFragment(String fragment) {
        String[] stopWords = {"怎么", "如何", "什么", "多少", "哪里", "为什么", "可以", "应该",
                "请问", "这个", "那个", "关于", "我的", "我想", "我想", "有没有"};
        for (String stop : stopWords) {
            if (fragment.contains(stop)) return true;
        }
        return false;
    }

    private List<String> extractDrugRelatedFragments(String text) {
        List<String> fragments = new ArrayList<>();
        String[] patterns = {
                "([^\\s]{2,10}?)(?:药品|药物|药)(?![品物])",
                "([^\\s]{2,10}?)(?:霉素|西林|酰胺|苷|片|胶囊|口服液|注射液|颗粒|滴眼液|软膏|贴剂|混悬液)",
                "(?:叫|名叫|名称为|是|的)([^\\s]{2,10}?)(?:药|药品)",
                "(?:关于)([^\\s]{2,10}?)(?:的)?",
                "(?:适龄|儿童|成人|孕妇|老人)?([^\\s]{2,8}?)(?:感冒|发烧|咳嗽|消炎|止痛|退烧|抗感染)",
        };

        for (String patternStr : patterns) {
            try {
                Pattern pattern = Pattern.compile(patternStr);
                Matcher matcher = pattern.matcher(text);
                while (matcher.find()) {
                    String fragment = matcher.group(1).trim();
                    if (fragment.length() >= 2 && !fragment.matches(".*[吗呢吧啊哦]")) {
                        fragments.add(fragment);
                    }
                }
            } catch (Exception e) {
                log.debug("Pattern not matched: {}", patternStr);
            }
        }

        return fragments;
    }

    private List<String> extractNGrams(String text, int minLen, int maxLen) {
        List<String> ngrams = new ArrayList<>();
        text = text.replaceAll("[\\s,.，。、！？!?\"\"''（）()\\[\\]]", "");

        for (int len = minLen; len <= maxLen && len <= text.length(); len++) {
            for (int i = 0; i <= text.length() - len; i++) {
                String ngram = text.substring(i, i + len);
                if (!isStopFragment(ngram)) {
                    ngrams.add(ngram);
                }
            }
        }
        return ngrams;
    }

    private double calculateSimilarity(String a, String b) {
        if (a == null || b == null) return 0;
        a = a.toLowerCase().trim();
        b = b.toLowerCase().trim();

        if (a.equals(b)) return 1.0;

        int distance = levenshteinDistance(a, b);
        int maxLen = Math.max(a.length(), b.length());
        double editSimilarity = 1.0 - (double) distance / maxLen;

        double lengthBonus = 0;
        if (a.length() >= 3 && b.startsWith(a.substring(0, Math.min(2, a.length())))) {
            lengthBonus = 0.1;
        }

        double charSetSimilarity = calculateCharSetSimilarity(a, b);

        return Math.min(1.0, editSimilarity * 0.6 + charSetSimilarity * 0.4 + lengthBonus);
    }

    private double calculateCharSetSimilarity(String a, String b) {
        Set<Character> setA = new HashSet<>();
        Set<Character> setB = new HashSet<>();
        for (char c : a.toCharArray()) setA.add(c);
        for (char c : b.toCharArray()) setB.add(c);

        Set<Character> intersection = new HashSet<>(setA);
        intersection.retainAll(setB);

        Set<Character> union = new HashSet<>(setA);
        union.addAll(setB);

        return union.isEmpty() ? 0 : (double) intersection.size() / union.size();
    }

    public Set<String> getAllStandardNames() {
        return allStandardNames;
    }

    public String getStandardName(String alias) {
        return aliasToStandard.get(alias);
    }

    private int levenshteinDistance(String a, String b) {
        if (a.isEmpty()) return b.length();
        if (b.isEmpty()) return a.length();

        int[][] dp = new int[a.length() + 1][b.length() + 1];

        for (int i = 0; i <= a.length(); i++) dp[i][0] = i;
        for (int j = 0; j <= b.length(); j++) dp[0][j] = j;

        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = (a.charAt(i - 1) == b.charAt(j - 1)) ? 0 : 1;
                dp[i][j] = Math.min(
                        Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                        dp[i - 1][j - 1] + cost
                );
            }
        }
        return dp[a.length()][b.length()];
    }

    @lombok.Data
    @lombok.AllArgsConstructor
    public static class DrugMatch {
        private String standardName;
        private String matchedAlias;
        private Double similarity;
        private String matchType;
    }
}
