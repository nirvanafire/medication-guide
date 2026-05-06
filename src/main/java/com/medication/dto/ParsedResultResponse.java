package com.medication.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ParsedResultResponse {

    private String fileName;
    private String fileType;
    private String drugName;
    private int totalSections;
    private int totalChunks;
    private List<SectionInfo> sections;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SectionInfo {
        private int index;
        private String sectionName;
        private int contentLength;
        private String contentPreview;
        private int chunkCount;
    }
}
