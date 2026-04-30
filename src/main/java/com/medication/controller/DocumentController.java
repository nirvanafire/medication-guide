package com.medication.controller;

import com.medication.dto.ApiResponse;
import com.medication.dto.DocumentResponse;
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