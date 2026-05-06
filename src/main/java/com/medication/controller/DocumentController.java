package com.medication.controller;

import com.medication.dto.ApiResponse;
import com.medication.dto.DocumentResponse;
import com.medication.dto.ParsedResultResponse;
import com.medication.entity.DrugDocument;
import com.medication.service.DocumentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 文档管理 API
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/drug-documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;

    /**
     * 上传药品说明书
     */
    @PostMapping("/upload")
    public ApiResponse<DocumentResponse.DocumentInfo> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam("drugName") String drugName) {
        try {
            DrugDocument doc = documentService.uploadDocument(file, drugName);
            return ApiResponse.success(toDocumentInfo(doc));
        } catch (Exception e) {
            log.error("文档上传失败", e);
            return ApiResponse.error("文档上传失败: " + e.getMessage());
        }
    }

    /**
     * 获取文档列表
     */
    @GetMapping
    public ApiResponse<List<DocumentResponse.DocumentInfo>> list(
            @RequestParam(required = false) String keyword) {
        List<DrugDocument> docs = documentService.listDocuments(keyword);
        List<DocumentResponse.DocumentInfo> infos = docs.stream()
                .map(this::toDocumentInfo)
                .collect(Collectors.toList());
        return ApiResponse.success(infos);
    }

    /**
     * 获取文档详情
     */
    @GetMapping("/{id}")
    public ApiResponse<DocumentResponse.DocumentInfo> get(@PathVariable Long id) {
        return documentService.getDocument(id)
                .map(doc -> ApiResponse.success(toDocumentInfo(doc)))
                .orElse(ApiResponse.error(404, "文档不存在"));
    }

    /**
     * 删除文档
     */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        try {
            documentService.deleteDocument(id);
            return ApiResponse.success(null);
        } catch (Exception e) {
            log.error("文档删除失败", e);
            return ApiResponse.error("文档删除失败: " + e.getMessage());
        }
    }

    /**
     * 解析文档预览
     * 上传 docx/pdf/md 文档，返回解析后的章节结构
     */
    @PostMapping("/parse-preview")
    public ApiResponse<ParsedResultResponse> parsePreview(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "drugName", required = false) String drugName) {
        try {
            String name = drugName != null && !drugName.isBlank() ? drugName : extractDrugName(file.getOriginalFilename());
            DocumentService.ParsedResult result = documentService.parseAndPreview(file, name);

            List<ParsedResultResponse.SectionInfo> sections = new java.util.ArrayList<>();
            for (int i = 0; i < result.getSections().size(); i++) {
                DocumentService.ParsedResult.SectionResult sr = result.getSections().get(i);
                String preview = sr.getContent().length() > 200
                        ? sr.getContent().substring(0, 200) + "..."
                        : sr.getContent();
                sections.add(ParsedResultResponse.SectionInfo.builder()
                        .index(i + 1)
                        .sectionName(sr.getSectionName())
                        .contentLength(sr.getContentLength())
                        .contentPreview(preview)
                        .chunkCount(sr.getChunkCount())
                        .build());
            }

            ParsedResultResponse response = ParsedResultResponse.builder()
                    .fileName(result.getFileName())
                    .fileType(result.getFileType())
                    .drugName(result.getDrugName())
                    .totalSections(result.getTotalSections())
                    .totalChunks(result.getTotalChunks())
                    .sections(sections)
                    .build();

            return ApiResponse.success(response);
        } catch (Exception e) {
            log.error("文档解析预览失败", e);
            return ApiResponse.error("文档解析失败: " + e.getMessage());
        }
    }

    /**
     * 重新拆分文档 chunk
     * 基于已存储的文档内容重新进行章节解析和 chunk 切分
     */
    @PostMapping("/{id}/rechunk")
    public ApiResponse<DocumentService.RechunkResult> rechunk(@PathVariable Long id) {
        try {
            DocumentService.RechunkResult result = documentService.rechunkDocument(id);
            return ApiResponse.success(result);
        } catch (Exception e) {
            log.error("重新拆分文档失败: id={}", id, e);
            return ApiResponse.error("重新拆分失败: " + e.getMessage());
        }
    }

    /**
     * 重新向量化文档
     * 删除旧向量并重新存入向量库
     */
    @PostMapping("/{id}/revectorize")
    public ApiResponse<DocumentService.RevectorizeResult> revectorize(@PathVariable Long id) {
        try {
            DocumentService.RevectorizeResult result = documentService.revectorizeDocument(id);
            return ApiResponse.success(result);
        } catch (Exception e) {
            log.error("重新向量化文档失败: id={}", id, e);
            return ApiResponse.error("重新向量化失败: " + e.getMessage());
        }
    }

    /**
     * 批量重新拆分所有文档 chunk
     */
    @PostMapping("/batch-rechunk")
    public ApiResponse<DocumentService.BatchRechunkResult> batchRechunk() {
        try {
            log.info("开始批量重新拆分所有文档");
            DocumentService.BatchRechunkResult result = documentService.rechunkAllDocuments();
            return ApiResponse.success(result);
        } catch (Exception e) {
            log.error("批量重新拆分文档失败", e);
            return ApiResponse.error("批量重新拆分失败: " + e.getMessage());
        }
    }

    /**
     * 批量重新向量化所有文档
     */
    @PostMapping("/batch-revectorize")
    public ApiResponse<DocumentService.BatchRevectorizeResult> batchRevectorize() {
        try {
            log.info("开始批量重新向量化所有文档");
            DocumentService.BatchRevectorizeResult result = documentService.revectorizeAllDocuments();
            return ApiResponse.success(result);
        } catch (Exception e) {
            log.error("批量重新向量化文档失败", e);
            return ApiResponse.error("批量重新向量化失败: " + e.getMessage());
        }
    }

    private String extractDrugName(String fileName) {
        if (fileName == null) return "未知药品";
        String name = fileName.replaceAll("\\.(docx|pdf|md|doc)$", "");
        return name;
    }

    private DocumentResponse.DocumentInfo toDocumentInfo(DrugDocument doc) {
        return DocumentResponse.DocumentInfo.builder()
                .id(doc.getId())
                .drugName(doc.getDrugName())
                .fileName(doc.getFileName())
                .fileType(doc.getFileType())
                .status(doc.getStatus() != null ? doc.getStatus().name() : "UNKNOWN")
                .chunkCount(doc.getChunkCount())
                .version(doc.getVersion())
                .uploadedAt(doc.getUploadedAt())
                .processedAt(doc.getProcessedAt())
                .build();
    }
}