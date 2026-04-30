package com.medication.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "query_log")
public class QueryLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String question;

    private String drugName;

    @Column(columnDefinition = "TEXT")
    private String answer;

    private Boolean hallucinationPassed;

    private Double confidenceScore;

    private Integer latencyMs;

    private String sourceSections;

    private Boolean cacheHit;

    private String sessionId;

    private String userId;

    private LocalDateTime createdAt;
}