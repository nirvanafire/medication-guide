-- =====================================================
-- MySQL Database Initialization Script
-- Database: medication_guide
-- Charset: utf8mb4
-- =====================================================

CREATE DATABASE IF NOT EXISTS `medication_guide`
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_general_ci;

USE `medication_guide`;

-- =====================================================
-- Table 1: drug_document (药品文档表)
-- =====================================================
DROP TABLE IF EXISTS `drug_document`;

CREATE TABLE `drug_document` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `drug_name` VARCHAR(255) NOT NULL COMMENT '药品名称',
    `file_name` VARCHAR(500) NOT NULL COMMENT '原始文件名',
    `file_path` VARCHAR(1000) DEFAULT '' COMMENT '文件存储路径',
    `file_type` VARCHAR(50) DEFAULT NULL COMMENT '文件类型(PDF/DOCX)',
    `content` LONGTEXT DEFAULT NULL COMMENT '完整文档内容',
    `sections` LONGTEXT DEFAULT NULL COMMENT '解析后的文档章节(JSON格式)',
    `chunk_count` INT DEFAULT 0 COMMENT '文档分块数量',
    `status` VARCHAR(20) NOT NULL DEFAULT 'PROCESSING' COMMENT '处理状态: PROCESSING/COMPLETED/FAILED',
    `version` VARCHAR(100) DEFAULT NULL COMMENT '文档版本号',
    `uploaded_at` DATETIME DEFAULT NULL COMMENT '上传时间',
    `processed_at` DATETIME DEFAULT NULL COMMENT '处理完成时间',
    `created_by` VARCHAR(100) DEFAULT NULL COMMENT '上传人',
    PRIMARY KEY (`id`),
    INDEX `idx_drug_name` (`drug_name`),
    INDEX `idx_status` (`status`),
    INDEX `idx_uploaded_at` (`uploaded_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='药品文档表';

-- =====================================================
-- Table 2: document_chunk (文档分块表)
-- =====================================================
DROP TABLE IF EXISTS `document_chunk`;

CREATE TABLE `document_chunk` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `document_id` BIGINT NOT NULL COMMENT '所属文档ID',
    `drug_name` VARCHAR(255) NOT NULL COMMENT '药品名称',
    `section` VARCHAR(255) NOT NULL COMMENT '所属章节(如:用法用量)',
    `chunk_index` INT NOT NULL COMMENT '在章节内的顺序',
    `content` LONGTEXT NOT NULL COMMENT '分块文本内容',
    `vector_id` VARCHAR(255) DEFAULT NULL COMMENT '向量数据库ID(Milvus)',
    `token_count` INT DEFAULT NULL COMMENT 'Token数量',
    `created_at` DATETIME DEFAULT NULL COMMENT '创建时间',
    PRIMARY KEY (`id`),
    INDEX `idx_document_id` (`document_id`),
    INDEX `idx_drug_name` (`drug_name`),
    INDEX `idx_section` (`section`),
    CONSTRAINT `fk_chunk_document` FOREIGN KEY (`document_id`)
        REFERENCES `drug_document` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='文档分块表';

-- =====================================================
-- Table 3: query_log (查询日志表)
-- =====================================================
DROP TABLE IF EXISTS `query_log`;

CREATE TABLE `query_log` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `question` LONGTEXT NOT NULL COMMENT '用户问题',
    `drug_name` VARCHAR(255) DEFAULT NULL COMMENT '关联药品名称',
    `answer` LONGTEXT DEFAULT NULL COMMENT 'LLM回答内容',
    `hallucination_passed` TINYINT(1) DEFAULT NULL COMMENT '幻觉检测是否通过: 1=通过, 0=未通过',
    `confidence_score` DOUBLE DEFAULT NULL COMMENT '置信度分数(0-1)',
    `latency_ms` INT DEFAULT NULL COMMENT '响应延迟(毫秒)',
    `source_sections` TEXT DEFAULT NULL COMMENT '引用的文档章节(JSON数组)',
    `cache_hit` TINYINT(1) DEFAULT 0 COMMENT '是否命中缓存: 1=是, 0=否',
    `session_id` VARCHAR(255) DEFAULT NULL COMMENT '会话ID',
    `user_id` VARCHAR(100) DEFAULT NULL COMMENT '用户ID',
    `created_at` DATETIME DEFAULT NULL COMMENT '查询时间',
    PRIMARY KEY (`id`),
    INDEX `idx_session_id` (`session_id`),
    INDEX `idx_user_id` (`user_id`),
    INDEX `idx_created_at` (`created_at`),
    INDEX `idx_drug_name` (`drug_name`),
    INDEX `idx_hallucination_passed` (`hallucination_passed`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='查询日志表';

-- =====================================================
-- Verify tables created
-- =====================================================
SELECT 'Tables created successfully:' AS status;
SHOW TABLES;
