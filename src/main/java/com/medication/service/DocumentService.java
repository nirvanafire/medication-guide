package com.medication.service;

import com.medication.entity.DrugDocument;
import com.medication.entity.DocumentChunk;
import com.medication.repository.DrugDocumentRepository;
import com.medication.repository.DocumentChunkRepository;
import com.medication.util.DocumentParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

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
    private final DocumentParser documentParser;

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
            // 解析文档内容
            String content = parseContent(file, file.getContentType());
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

    private String parseContent(MultipartFile file, String contentType) throws IOException {
        String content = new String(file.getBytes(), "UTF-8");

        // 简单的文本处理
        content = content.replaceAll("\\s+", " ")
                .replaceAll("【.*?】", "\n【$1】\n");

        return content.trim();
    }

    private String getFileType(String fileName) {
        if (fileName == null) return "unknown";
        int lastDot = fileName.lastIndexOf('.');
        return lastDot > 0 ? fileName.substring(lastDot + 1).toLowerCase() : "unknown";
    }
}