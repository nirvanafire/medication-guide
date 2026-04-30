package com.medication.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "drug_document")
public class DrugDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String drugName;

    @Column(nullable = false)
    private String fileName;

    @Column(nullable = false)
    private String filePath;

    @Column(length = 50)
    private String fileType;

    @Column(columnDefinition = "TEXT")
    private String content;

    @Column(columnDefinition = "TEXT")
    private String sections;

    private Integer chunkCount;

    @Enumerated(EnumType.STRING)
    private DocumentStatus status = DocumentStatus.PROCESSING;

    private String version;

    private LocalDateTime uploadedAt;

    private LocalDateTime processedAt;

    @Column(name = "created_by")
    private String createdBy;

    public enum DocumentStatus {
        PROCESSING, COMPLETED, FAILED
    }
}