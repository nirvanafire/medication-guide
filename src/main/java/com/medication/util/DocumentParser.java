package com.medication.util;

import com.medication.config.RagConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 药品说明书文档解析器
 * 支持按标准药典章节结构进行智能分段
 */
@Slf4j
@Component
public class DocumentParser {

    private static final List<String> STANDARD_SECTIONS = Arrays.asList(
            "通用名称", "商品名称", "英文名称", "汉语拼音",
            "成份", "主要成份", "辅料",
            "性状", "适应症", "功能主治",
            "规格", "用法用量",
            "不良反应", "禁忌",
            "注意事项", "孕妇及哺乳期妇女用药",
            "儿童用药", "老年用药",
            "药物相互作用", "药物过量",
            "临床试验", "药理毒理", "药代动力学",
            "贮藏", "包装", "有效期",
            "执行标准", "批准文号", "生产企业",
            "生产企业名称", "生产地址"
    );

    private static final Map<String, List<String>> SECTION_SPLIT_RULES = new HashMap<>();

    static {
        SECTION_SPLIT_RULES.put("成份", Arrays.asList("活性成份", "辅料"));
        SECTION_SPLIT_RULES.put("用法用量", Arrays.asList("成人", "儿童", "老年", "肾功能不全", "肝功能不全"));
        SECTION_SPLIT_RULES.put("不良反应", Arrays.asList("常见", "偶见", "罕见", "严重", "上市后"));
        SECTION_SPLIT_RULES.put("注意事项", null); // 按条目分段
    }

    private final RagConfig ragConfig;

    public DocumentParser(RagConfig ragConfig) {
        this.ragConfig = ragConfig;
    }

    /**
     * 从原始文本中解析出结构化章节
     */
    public List<ParsedSection> parseDocument(String text, String drugName) {
        List<ParsedSection> sections = new ArrayList<>();
        List<TextBlock> blocks = splitBySections(text);

        String currentSection = "概述";
        StringBuilder currentContent = new StringBuilder();

        for (TextBlock block : blocks) {
            if (isSectionHeader(block.text)) {
                if (currentContent.length() > 0) {
                    sections.add(new ParsedSection(drugName, currentSection, currentContent.toString().trim()));
                }
                currentSection = normalizeSectionName(block.text);
                currentContent = new StringBuilder();
            } else {
                if (currentContent.length() > 0) {
                    currentContent.append("\n");
                }
                currentContent.append(block.text.trim());
            }
        }

        if (currentContent.length() > 0) {
            sections.add(new ParsedSection(drugName, currentSection, currentContent.toString().trim()));
        }

        log.info("文档解析完成: {} 共解析出 {} 个章节", drugName, sections.size());
        return sections;
    }

    /**
     * 将章节内容切分为 chunk
     */
    public List<Chunk> chunkSection(ParsedSection section) {
        List<Chunk> chunks = new ArrayList<>();
        String content = section.getContent();

        if (content == null || content.isBlank()) {
            return chunks;
        }

        // 按规则子分段
        List<String> subBlocks = smartSplit(section.getSection(), content);
        int chunkSize = ragConfig.getChunk().getSize();
        int overlap = ragConfig.getChunk().getOverlap();

        for (String block : subBlocks) {
            List<String> textChunks = splitByTokenSize(block, chunkSize, overlap);
            for (int i = 0; i < textChunks.size(); i++) {
                chunks.add(new Chunk(
                        section.getDrugName(),
                        section.getSection(),
                        i,
                        textChunks.get(i),
                        estimateTokens(textChunks.get(i))
                ));
            }
        }

        log.debug("章节 [{}] 切分为 {} 个 chunk", section.getSection(), chunks.size());
        return chunks;
    }

    /**
     * 智能分段：根据章节类型采用不同策略
     */
    private List<String> smartSplit(String sectionName, String content) {
        // 整段保留的章节
        List<String> keepWhole = Arrays.asList("通用名称", "商品名称", "适应症", "禁忌", "规格", "贮藏", "包装", "有效期");
        if (keepWhole.contains(sectionName)) {
            return Arrays.asList(content);
        }

        // 按条目分段的章节
        if ("注意事项".equals(sectionName)) {
            return splitByItems(content);
        }

        // 按规则分段
        List<String> rules = SECTION_SPLIT_RULES.get(sectionName);
        if (rules != null) {
            return splitByRules(content, rules);
        }

        // 默认按段落分段
        return splitByParagraphs(content);
    }

    private List<TextBlock> splitBySections(String text) {
        List<TextBlock> blocks = new ArrayList<>();
        String[] lines = text.split("\n");

        Pattern sectionPattern = Pattern.compile(
                "^\\s*([一二三四五六七八九十]+[、.．]|\\d+[、.．]|" +
                String.join("|", STANDARD_SECTIONS.stream().map(Pattern::quote).collect(Collectors.joining("|"))) +
                ")\\s*(.*)"
        );

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            Matcher m = sectionPattern.matcher(trimmed);
            if (m.matches() || STANDARD_SECTIONS.contains(trimmed)) {
                blocks.add(new TextBlock(trimmed, true));
            } else {
                blocks.add(new TextBlock(trimmed, false));
            }
        }

        return blocks;
    }

    private List<String> splitByTokenSize(String text, int chunkSize, int overlap) {
        List<String> chunks = new ArrayList<>();
        if (text.length() <= chunkSize * 3) {
            chunks.add(text);
            return chunks;
        }

        int step = chunkSize * 3 - overlap * 3;
        for (int i = 0; i < text.length(); i += step) {
            int end = Math.min(i + chunkSize * 3, text.length());
            String chunk = text.substring(i, end);
            chunks.add(chunk);
            if (end >= text.length()) break;
        }
        return chunks;
    }

    private List<String> splitByItems(String content) {
        return Arrays.asList(content.split("(?<=\\d+[.、)）])"));
    }

    private List<String> splitByRules(String content, List<String> rules) {
        List<String> blocks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        String[] lines = content.split("\n");

        for (String line : lines) {
            boolean matched = rules.stream().anyMatch(rule -> line.contains(rule));
            if (matched && current.length() > 0) {
                blocks.add(current.toString().trim());
                current = new StringBuilder();
            }
            current.append(line).append("\n");
        }
        if (current.length() > 0) {
            blocks.add(current.toString().trim());
        }
        return blocks;
    }

    private List<String> splitByParagraphs(String content) {
        return Arrays.asList(content.split("\n\n+"));
    }

    private boolean isSectionHeader(String text) {
        if (STANDARD_SECTIONS.contains(text)) return true;
        if (text.length() <= 20) {
            return STANDARD_SECTIONS.stream().anyMatch(s -> text.contains(s) || s.contains(text));
        }
        return false;
    }

    private String normalizeSectionName(String name) {
        String cleaned = name.replaceAll("^[一二三四五六七八九十]+[、.．]\\s*", "")
                .replaceAll("^\\d+[、.．]\\s*", "")
                .replaceAll("[:：]", "")
                .trim();
        return STANDARD_SECTIONS.stream()
                .filter(s -> cleaned.contains(s) || s.contains(cleaned))
                .findFirst()
                .orElse(cleaned.length() <= 10 ? cleaned : "概述");
    }

    private int estimateTokens(String text) {
        return text.length() / 2; // 中文约2字符/token
    }

    @lombok.Data
    @lombok.AllArgsConstructor
    public static class TextBlock {
        private String text;
        private boolean isSectionHeader;
    }

    @lombok.Data
    @lombok.AllArgsConstructor
    @lombok.NoArgsConstructor
    public static class ParsedSection {
        private String drugName;
        private String section;
        private String content;
    }

    @lombok.Data
    @lombok.AllArgsConstructor
    @lombok.NoArgsConstructor
    public static class Chunk {
        private String drugName;
        private String section;
        private Integer chunkIndex;
        private String content;
        private Integer tokenCount;
    }
}