package com.medication.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "drug_alias", indexes = {
    @Index(name = "idx_alias_name", columnList = "aliasName"),
    @Index(name = "idx_standard_name", columnList = "standardName")
})
public class DrugAlias {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "standard_name", nullable = false, length = 100)
    private String standardName;

    @Column(name = "alias_name", nullable = false, length = 100)
    private String aliasName;

    @Column(name = "alias_type", length = 20)
    @Enumerated(EnumType.STRING)
    private AliasType aliasType = AliasType.COMMON;

    @Column(name = "priority")
    private Integer priority = 0;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    public enum AliasType {
        COMMON,      // 通用名
        TRADEMARK,   // 商品名
        PINYIN,      // 拼音
        CHEMICAL     // 化学名
    }
}
