package com.medication.service;

import com.medication.entity.DrugDocument;
import com.medication.entity.DocumentChunk;
import com.medication.repository.DrugDocumentRepository;
import com.medication.repository.DocumentChunkRepository;
import com.medication.util.BM25Scorer;
import com.medication.util.DocumentParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文档管理服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentService {

    private final DrugDocumentRepository documentRepository;
    private final DocumentChunkRepository chunkRepository;
    private final VectorStoreService vectorStoreService;
    private final BM25Scorer bm25Scorer;
    private final DocumentParser documentParser;

    @org.springframework.beans.factory.annotation.Autowired
    private DocumentService self;

    @Transactional
    public DrugDocument uploadDocument(MultipartFile file, String drugName) throws IOException {
        log.info("开始上传文档: {} -> {}", file.getOriginalFilename(), drugName);

        // 保存文档元数据
        DrugDocument doc = new DrugDocument();
        doc.setDrugName(drugName);
        doc.setFileName(file.getOriginalFilename());
        doc.setFileType(getFileType(file.getOriginalFilename()));
        doc.setUploadedAt(LocalDateTime.now());
        doc.setStatus(DrugDocument.DocumentStatus.PROCESSING);

        doc = documentRepository.save(doc);

        try {
            // 解析文档内容（使用文件扩展名而非 content-type，更可靠）
            String fileType = getFileType(file.getOriginalFilename());
            String content = parseContent(file, fileType);
            doc.setContent(content);

            // 按章节解析
            List<DocumentParser.ParsedSection> sections = documentParser.parseDocument(content, drugName);
            doc.setSections(String.join(",", sections.stream().map(DocumentParser.ParsedSection::getSection).toList()));

            // 切分 Chunk
            List<DocumentParser.Chunk> allChunks = sections.stream()
                    .flatMap(section -> documentParser.chunkSection(section).stream())
                    .toList();

            doc.setChunkCount(allChunks.size());

            // 存入向量库
            vectorStoreService.storeChunks(allChunks);

            // 存入数据库
            for (DocumentParser.Chunk chunk : allChunks) {
                DocumentChunk chunkEntity = new DocumentChunk();
                chunkEntity.setDocumentId(doc.getId());
                chunkEntity.setDrugName(chunk.getDrugName());
                chunkEntity.setSection(chunk.getSection());
                chunkEntity.setChunkIndex(chunk.getChunkIndex());
                chunkEntity.setContent(chunk.getContent());
                chunkEntity.setTokenCount(chunk.getTokenCount());
                chunkEntity.setCreatedAt(LocalDateTime.now());
                chunkRepository.save(chunkEntity);
            }

            doc.setStatus(DrugDocument.DocumentStatus.COMPLETED);
            doc.setProcessedAt(LocalDateTime.now());
            doc = documentRepository.save(doc);

            log.info("文档处理完成: {} chunks={}", drugName, allChunks.size());
        } catch (Exception e) {
            doc.setStatus(DrugDocument.DocumentStatus.FAILED);
            documentRepository.save(doc);
            throw e;
        }

        return doc;
    }

    public Optional<DrugDocument> getDocument(Long id) {
        return documentRepository.findById(id);
    }

    public List<DrugDocument> listDocuments(String keyword) {
        if (keyword != null && !keyword.isBlank()) {
            return documentRepository.findByDrugNameContaining(keyword);
        }
        return documentRepository.findAll();
    }

    @Transactional
    public void deleteDocument(Long id) {
        DrugDocument doc = documentRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("文档不存在"));

        // 删除向量数据
        vectorStoreService.deleteByDrugName(doc.getDrugName());

        // 删除数据库记录
        chunkRepository.deleteByDocumentId(id);
        documentRepository.deleteById(id);

        log.info("文档已删除: {}", doc.getDrugName());
    }

    @Transactional
    public RechunkResult rechunkDocument(Long id) {
        log.info("重新拆分文档 chunk: id={}", id);

        DrugDocument doc = documentRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("文档不存在"));

        if (doc.getContent() == null || doc.getContent().isBlank()) {
            throw new RuntimeException("文档内容为空，请重新上传文档");
        }

        doc.setStatus(DrugDocument.DocumentStatus.PROCESSING);
        documentRepository.save(doc);

        try {
            // 删除旧的 chunks
            chunkRepository.deleteByDocumentId(id);
            vectorStoreService.deleteByDrugName(doc.getDrugName());

            // 重新解析并切分
            List<DocumentParser.ParsedSection> sections = documentParser.parseDocument(doc.getContent(), doc.getDrugName());
            List<DocumentParser.Chunk> allChunks = sections.stream()
                    .flatMap(section -> documentParser.chunkSection(section).stream())
                    .toList();

            // 更新元数据
            doc.setSections(String.join(",", sections.stream().map(DocumentParser.ParsedSection::getSection).toList()));
            doc.setChunkCount(allChunks.size());

            // 存入向量库
            vectorStoreService.storeChunks(allChunks);

            // 存入数据库
            for (DocumentParser.Chunk chunk : allChunks) {
                DocumentChunk chunkEntity = new DocumentChunk();
                chunkEntity.setDocumentId(doc.getId());
                chunkEntity.setDrugName(chunk.getDrugName());
                chunkEntity.setSection(chunk.getSection());
                chunkEntity.setChunkIndex(chunk.getChunkIndex());
                chunkEntity.setContent(chunk.getContent());
                chunkEntity.setTokenCount(chunk.getTokenCount());
                chunkEntity.setCreatedAt(LocalDateTime.now());
                chunkRepository.save(chunkEntity);
            }

            doc.setStatus(DrugDocument.DocumentStatus.COMPLETED);
            doc.setProcessedAt(LocalDateTime.now());
            documentRepository.save(doc);

            log.info("文档重新拆分完成: {} chunks={}", doc.getDrugName(), allChunks.size());
            return new RechunkResult(doc.getId(), doc.getDrugName(), sections.size(), allChunks.size());
        } catch (Exception e) {
            doc.setStatus(DrugDocument.DocumentStatus.FAILED);
            documentRepository.save(doc);
            throw e;
        }
    }

    @Transactional
    public RevectorizeResult revectorizeDocument(Long id) {
        log.info("重新向量化文档: id={}", id);

        DrugDocument doc = documentRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("文档不存在"));

        doc.setStatus(DrugDocument.DocumentStatus.PROCESSING);
        documentRepository.save(doc);

        try {
            // 获取现有 chunks
            List<DocumentChunk> existingChunks = chunkRepository.findByDocumentId(id);
            if (existingChunks.isEmpty()) {
                throw new RuntimeException("文档没有 chunk 数据，请先执行重新拆分");
            }

            // 删除旧向量
            vectorStoreService.deleteByDrugName(doc.getDrugName());

            // 转换为 Chunk 并重新存入
            List<DocumentParser.Chunk> chunks = existingChunks.stream()
                    .map(c -> new DocumentParser.Chunk(
                            c.getDrugName(),
                            c.getSection(),
                            c.getChunkIndex(),
                            c.getContent(),
                            c.getTokenCount()))
                    .toList();

            vectorStoreService.storeChunks(chunks);

            doc.setStatus(DrugDocument.DocumentStatus.COMPLETED);
            doc.setProcessedAt(LocalDateTime.now());
            documentRepository.save(doc);

            log.info("文档重新向量化完成: {} chunks={}", doc.getDrugName(), chunks.size());
            return new RevectorizeResult(doc.getId(), doc.getDrugName(), chunks.size());
        } catch (Exception e) {
            doc.setStatus(DrugDocument.DocumentStatus.FAILED);
            documentRepository.save(doc);
            throw e;
        }
    }

    @Transactional
    public BatchRechunkResult rechunkAllDocuments() {
        log.info("开始批量重新拆分所有文档");

        List<DrugDocument> allDocs = documentRepository.findAll();
        List<BatchItemResult> results = new java.util.ArrayList<>();
        int successCount = 0;
        int failCount = 0;

        for (DrugDocument doc : allDocs) {
            try {
                RechunkResult result = self.rechunkDocument(doc.getId());
                results.add(new BatchItemResult(result.getDocumentId(), result.getDrugName(), result.getTotalChunks(), true, null));
                successCount++;
            } catch (Exception e) {
                log.error("批量重新拆分文档失败: id={}, drug={}", doc.getId(), doc.getDrugName(), e);
                results.add(new BatchItemResult(doc.getId(), doc.getDrugName(), 0, false, e.getMessage()));
                failCount++;
            }
        }

        log.info("批量重新拆分完成: 成功={}, 失败={}", successCount, failCount);
        return new BatchRechunkResult(successCount, failCount, results);
    }

    @Transactional
    public BatchRevectorizeResult revectorizeAllDocuments(boolean clearFirst) {
        log.info("开始批量重新向量化所有文档, clearFirst={}", clearFirst);

        if (clearFirst) {
            vectorStoreService.deleteAll();
            bm25Scorer.clear();
            log.info("已清空向量库和BM25索引");
        }

        List<DrugDocument> allDocs = documentRepository.findAll();
        List<BatchItemResult> results = new java.util.ArrayList<>();
        int successCount = 0;
        int failCount = 0;

        for (DrugDocument doc : allDocs) {
            try {
                RevectorizeResult result = self.revectorizeDocument(doc.getId());
                results.add(new BatchItemResult(result.getDocumentId(), result.getDrugName(), result.getTotalChunks(), true, null));
                successCount++;
            } catch (Exception e) {
                log.error("批量重新向量化文档失败: id={}, drug={}", doc.getId(), doc.getDrugName(), e);
                results.add(new BatchItemResult(doc.getId(), doc.getDrugName(), 0, false, e.getMessage()));
                failCount++;
            }
        }

        log.info("批量重新向量化完成: 成功={}, 失败={}", successCount, failCount);
        return new BatchRevectorizeResult(successCount, failCount, results);
    }

    public ParsedResult parseAndPreview(MultipartFile file, String drugName) throws IOException {
        log.info("预览解析文档: {} -> {}", file.getOriginalFilename(), drugName);

        String fileType = getFileType(file.getOriginalFilename());
        String content = parseContent(file, fileType);

        List<DocumentParser.ParsedSection> sections = documentParser.parseDocument(content, drugName);

        List<ParsedResult.SectionResult> sectionResults = new java.util.ArrayList<>();
        int totalChunks = 0;
        for (DocumentParser.ParsedSection section : sections) {
            List<DocumentParser.Chunk> chunks = documentParser.chunkSection(section);
            totalChunks += chunks.size();
            sectionResults.add(new ParsedResult.SectionResult(
                    section.getSection(),
                    section.getContent().length(),
                    section.getContent(),
                    chunks.size()
            ));
        }

        return new ParsedResult(
                file.getOriginalFilename(),
                fileType,
                drugName,
                sections.size(),
                totalChunks,
                sectionResults
        );
    }

    @lombok.Value
    public static class ParsedResult {
        String fileName;
        String fileType;
        String drugName;
        int totalSections;
        int totalChunks;
        List<SectionResult> sections;

        @lombok.Value
        public static class SectionResult {
            String sectionName;
            int contentLength;
            String content;
            int chunkCount;
        }
    }

    @lombok.Value
    public static class RechunkResult {
        Long documentId;
        String drugName;
        int totalSections;
        int totalChunks;
    }

    @lombok.Value
    public static class RevectorizeResult {
        Long documentId;
        String drugName;
        int totalChunks;
    }

    @lombok.Value
    public static class BatchRechunkResult {
        int successCount;
        int failCount;
        List<BatchItemResult> results;
    }

    @lombok.Value
    public static class BatchRevectorizeResult {
        int successCount;
        int failCount;
        List<BatchItemResult> results;
    }

    @lombok.Value
    public static class BatchItemResult {
        Long documentId;
        String drugName;
        int chunkCount;
        boolean success;
        String errorMessage;
    }

    private static final Pattern TAG_PATTERN = Pattern.compile("【([^】]+】)");

    private String parseContent(MultipartFile file, String fileType) throws IOException {
        String content;
        String lowerType = fileType != null ? fileType.toLowerCase() : "";

        if ("docx".equals(lowerType) || "pdf".equals(lowerType)) {
            content = parseDocxContent(file.getInputStream());
        } else {
            // md or txt or unknown
            content = new String(file.getBytes(), "UTF-8");
            content = content.replaceAll("\\s+", " ")
                    .replaceAll("【.*?】", "\n【$1】\n");
        }

        return content.trim();
    }

    private String parseDocxContent(InputStream inputStream) throws IOException {
        StringBuilder text = new StringBuilder();
        try (XWPFDocument document = new XWPFDocument(inputStream)) {
            for (XWPFParagraph paragraph : document.getParagraphs()) {
                String paraText = paragraph.getText();
                if (paraText != null && !paraText.isBlank()) {
                    text.append(paraText).append("\n");
                    Matcher matcher = TAG_PATTERN.matcher(paraText);
                    if (matcher.find()) {
                        text.append("\n");
                    }
                }
            }
        }
        return text.toString();
    }

    private String getFileType(String fileName) {
        if (fileName == null) return "unknown";
        int lastDot = fileName.lastIndexOf('.');
        return lastDot > 0 ? fileName.substring(lastDot + 1).toLowerCase() : "unknown";
    }
}