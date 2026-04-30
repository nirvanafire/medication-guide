package com.medication.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentResponse {

    private Integer code;
    private Object data;
    private String message;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DocumentInfo {
        private Long id;
        private String drugName;
        private String fileName;
        private String fileType;
        private String status;
        private Integer chunkCount;
        private String version;
        private LocalDateTime uploadedAt;
        private LocalDateTime processedAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DocumentList {
        private List<DocumentInfo> documents;
        private Long total;
        private Integer page;
        private Integer size;
    }
}