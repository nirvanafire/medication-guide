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
 * 流程：文档解析 → 文本清洗 → 智能分段 → Chunk分块 → Embedding → 存入向量库
 * 分块策略：每块400token，重叠50token，优先按章节边界切分
 * 每块携带元数据：drug_name、section、chunk_index
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
            "注意事项", "孕妇及哺乳期妇女用药", "妊娠与哺乳期注意事项",
            "儿童用药", "儿童注意事项", "老年用药", "老人注意事项",
            "药物相互作用", "药物过量",
            "临床试验", "药理毒理", "药代动力学", "药理作用",
            "贮藏", "包装", "有效期",
            "执行标准", "批准文号", "生产企业", "企业名称", "企业简称"
    );

    // 分段策略定义：整段保留 vs 按规则分段
    private static final List<String> KEEP_WHOLE_SECTIONS = Arrays.asList(
            "通用名称", "商品名称", "英文名称", "汉语拼音",
            "适应症", "功能主治", "禁忌", "规格",
            "性状", "贮藏", "包装", "有效期",
            "执行标准", "批准文号", "生产企业", "企业名称", "企业简称"
    );

    private static final Map<String, List<String>> SECTION_SPLIT_RULES = new HashMap<>();

    static {
        // 按活性成份/辅料分段
        SECTION_SPLIT_RULES.put("成份", Arrays.asList("活性成份", "辅料", "主要成份"));
        // 按年龄段/病种分段
        SECTION_SPLIT_RULES.put("用法用量", Arrays.asList("成人", "小儿", "儿童", "婴儿", "老年", "肾功能", "肝功能", "孕", "哺乳"));
        // 按系统分类分段
        SECTION_SPLIT_RULES.put("不良反应", Arrays.asList("胃肠道", "过敏", "血液", "肝脏", "神经", "二重感染", "常见", "偶见", "罕见", "严重", "上市后"));
        // 按条目分段
        SECTION_SPLIT_RULES.put("注意事项", Arrays.asList("过敏", "皮试", "传染性单核细胞", "肝肾", "尿糖", "妊娠", "哺乳", "儿童", "老年"));
        SECTION_SPLIT_RULES.put("儿童注意事项", Arrays.asList("丙磺舒", "氯霉素", "大环内酯", "磺胺", "四环素"));
        SECTION_SPLIT_RULES.put("妊娠与哺乳期注意事项", Arrays.asList("动物", "生殖", "孕妇", "哺乳", "乳汁"));
        SECTION_SPLIT_RULES.put("老人注意事项", Arrays.asList("老年", "肾功能"));
        // 按药理分类分段
        SECTION_SPLIT_RULES.put("药理作用", Arrays.asList("抗菌", "杀菌", "细胞壁", "机制"));
        // 药物相互作用按药物类别分段
        SECTION_SPLIT_RULES.put("药物相互作用", Arrays.asList("丙磺舒", "氯霉素", "大环内酯", "磺胺", "四环素", "氨基糖苷"));
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

        log.info("共分割出 {} 个文本块", blocks.size());

        String currentSection = "概述";
        String currentContent = "";
        boolean pendingSection = false; // 标记是否有待保存的章节

        for (int i = 0; i < blocks.size(); i++) {
            TextBlock block = blocks.get(i);
            log.debug("处理块[{}]: isSection={}, text={}", i, block.isSectionHeader, block.text.substring(0, Math.min(50, block.text.length())));

            if (block.isSectionHeader) {
                // 先保存上一个章节（只要遇到新的章节标题就保存上一个）
                if (pendingSection) {
                    sections.add(new ParsedSection(drugName, currentSection, currentContent.trim()));
                    log.info("保存章节 [{}]: {} 字符", currentSection, currentContent.length());
                }
                // 提取标签名和内容
                currentSection = extractSectionName(block.text);
                currentContent = extractContentAfterTag(block.text);
                pendingSection = true;
                log.debug("提取到章节 [{}], 当前内容长度: {}", currentSection, currentContent.length());

                // 如果当前标签行没有后续内容，检查下一个块是否是内容块
                if (currentContent.isEmpty() && i + 1 < blocks.size() && !blocks.get(i + 1).isSectionHeader) {
                    // 下一个块是内容，追加到当前章节
                    currentContent = blocks.get(i + 1).text.trim();
                    i++; // 跳过下一个块
                    log.debug("从下一个块补充内容，长度: {}", currentContent.length());
                }
            } else {
                // 非标签行累积内容
                if (!currentContent.isEmpty()) {
                    currentContent += "\n";
                }
                currentContent += block.text.trim();
            }
        }

        // 保存最后一个章节
        if (pendingSection && !currentContent.isEmpty()) {
            sections.add(new ParsedSection(drugName, currentSection, currentContent.trim()));
            log.info("保存章节 [{}]: {} 字符", currentSection, currentContent.length());
        }

        log.info("文档解析完成: {} 共解析出 {} 个章节", drugName, sections.size());
        for (ParsedSection s : sections) {
            log.debug("章节: [{}] 内容长度: {}", s.getSection(), s.getContent().length());
        }
        return sections;
    }

    private String extractSectionName(String text) {
        Matcher tagMatcher = SECTION_TAG_PATTERN.matcher(text);
        if (tagMatcher.find()) {
            return tagMatcher.group(1);
        }
        // 清理前缀和分隔符
        String cleaned = text.replaceAll("^[一二三四五六七八九十]+[、.．]\\s*", "")
                .replaceAll("^\\d+[、.．]\\s*", "")
                .replaceAll("[:：]", "")
                .trim();
        return SECTION_SET.stream()
                .filter(s -> cleaned.contains(s) || s.contains(cleaned))
                .findFirst()
                .orElse(cleaned.length() <= 10 ? cleaned : "概述");
    }

    private String extractContentAfterTag(String text) {
        Matcher tagMatcher = SECTION_TAG_PREFIX_PATTERN.matcher(text);
        if (tagMatcher.find()) {
            // 返回标签后的所有内容（去掉开头的 : 和空格）
            String afterTag = text.substring(tagMatcher.end()).replaceFirst("^[:：]\\s*", "");
            return afterTag;
        }
        return "";
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
     *
     * 分段策略（按药典规范）：
     * - 通用名称/商品名称：整段保留
     * - 成份：按活性成份/辅料分段
     * - 适应症：整段保留
     * - 用法用量：按年龄段/病种分段
     * - 不良反应：按系统分类分段
     * - 禁忌：整段保留
     * - 注意事项：按条目分段
     */
    private List<String> smartSplit(String sectionName, String content) {
        log.debug("smartSplit 开始处理章节 [{}], 内容长度: {}", sectionName, content.length());

        // 整段保留的章节
        if (KEEP_WHOLE_SECTIONS.contains(sectionName)) {
            log.debug("章节 [{}] 整段保留", sectionName);
            return Arrays.asList(content);
        }

        // 按条目分段的章节（如注意事项）
        if ("注意事项".equals(sectionName) || "儿童注意事项".equals(sectionName)
            || "妊娠与哺乳期注意事项".equals(sectionName) || "老人注意事项".equals(sectionName)) {
            List<String> items = splitByItems(content);
            log.debug("章节 [{}] 按条目分段, 得到 {} 个子块", sectionName, items.size());
            return items;
        }

        // 按规则分段
        List<String> rules = SECTION_SPLIT_RULES.get(sectionName);
        if (rules != null) {
            List<String> blocks = splitByRules(content, rules);
            log.debug("章节 [{}] 按规则分段, 得到 {} 个子块", sectionName, blocks.size());
            return blocks;
        }

        // 默认按段落分段
        List<String> paras = splitByParagraphs(content);
        log.debug("章节 [{}] 按段落分段, 得到 {} 个子块", sectionName, paras.size());
        return paras;
    }

    private static final Pattern SECTION_TAG_PATTERN = Pattern.compile("^【([^】]+)】\\s*(:.*)?$");
    private static final Pattern SECTION_TAG_PREFIX_PATTERN = Pattern.compile("^【([^】]+)】");
    private static final Set<String> SECTION_SET = new HashSet<>(STANDARD_SECTIONS);

    private static final Pattern CHINESE_NUMBER_PATTERN = Pattern.compile("^[一二三四五六七八九十]+[、.．]");
    private static final Pattern ARABIC_NUMBER_PATTERN = Pattern.compile("^\\d+[、.．]");

    private List<TextBlock> splitBySections(String text) {
        List<TextBlock> blocks = new ArrayList<>();
        String[] lines = text.split("\n");

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;

            // 匹配 【标签】 格式（严格匹配整行）
            Matcher tagMatcher = SECTION_TAG_PATTERN.matcher(trimmed);
            if (tagMatcher.matches()) {
                String tag = tagMatcher.group(1);
                blocks.add(new TextBlock(trimmed, true));
                log.debug("识别章节标签: {} -> [{}]", trimmed, tag);
                continue;
            }

            // 匹配中文序号（一、二、三、）
            Matcher cnMatcher = CHINESE_NUMBER_PATTERN.matcher(trimmed);
            if (cnMatcher.find() && trimmed.length() <= 50) {
                blocks.add(new TextBlock(trimmed, true));
                log.debug("识别中文序号: {}", trimmed);
                continue;
            }

            // 匹配阿拉伯数字序号（1、2、）
            Matcher numMatcher = ARABIC_NUMBER_PATTERN.matcher(trimmed);
            if (numMatcher.find() && trimmed.length() <= 50) {
                blocks.add(new TextBlock(trimmed, true));
                log.debug("识别数字序号: {}", trimmed);
                continue;
            }

            // 匹配标准章节名
            boolean isSection = SECTION_SET.contains(trimmed) ||
                    (trimmed.length() <= 30 && SECTION_SET.stream().anyMatch(s -> trimmed.contains(s)));

            blocks.add(new TextBlock(trimmed, isSection));
        }

        log.debug("splitBySections 共分割出 {} 个块", blocks.size());
        return blocks;
    }

    private List<String> splitByTokenSize(String text, int chunkSize, int overlap) {
        List<String> chunks = new ArrayList<>();
        // 修正：chunkSize 和 overlap 单位是 token（约2字符/token）
        int chunkSizeInChars = chunkSize * 2;
        int overlapInChars = overlap * 2;

        if (text.length() <= chunkSizeInChars) {
            chunks.add(text);
            return chunks;
        }

        int step = chunkSizeInChars - overlapInChars;
        for (int i = 0; i < text.length(); i += step) {
            int end = Math.min(i + chunkSizeInChars, text.length());
            String chunk = text.substring(i, end);
            chunks.add(chunk);
            if (end >= text.length()) break;
        }
        return chunks;
    }

    private List<String> splitByItems(String content) {
        // 按条目序号分段：支持 1. 2. 或 1、2、 或 1 2 格式
        String[] parts = content.split("(?<=\\d)[\\s　]*(?=\\d)|(?<=\\d[.、)）])");
        List<String> nonEmpty = new ArrayList<>();
        for (String part : parts) {
            if (part != null && !part.trim().isEmpty()) {
                nonEmpty.add(part.trim());
            }
        }
        if (nonEmpty.isEmpty()) {
            nonEmpty.add(content);
        }
        return nonEmpty;
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
        Matcher tagMatcher = SECTION_TAG_PATTERN.matcher(text);
        if (tagMatcher.find()) {
            log.trace("isSectionHeader: {} -> true (tag matched)", text);
            return true;
        }
        if (SECTION_SET.contains(text)) {
            log.trace("isSectionHeader: {} -> true (in SECTION_SET)", text);
            return true;
        }
        if (text.length() <= 20 && SECTION_SET.stream().anyMatch(s -> text.contains(s) || s.contains(text))) {
            log.trace("isSectionHeader: {} -> true (partial match)", text);
            return true;
        }
        log.trace("isSectionHeader: {} -> false", text);
        return false;
    }

    private String normalizeSectionName(String name) {
        // 提取【】中的标签内容
        Matcher tagMatcher = SECTION_TAG_PATTERN.matcher(name);
        if (tagMatcher.find()) {
            name = tagMatcher.group(1);
        }

        // 清理前缀和分隔符
        String cleaned = name.replaceAll("^[一二三四五六七八九十]+[、.．]\\s*", "")
                .replaceAll("^\\d+[、.．]\\s*", "")
                .replaceAll("[:：]", "")
                .trim();

        // 匹配标准章节名
        return SECTION_SET.stream()
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