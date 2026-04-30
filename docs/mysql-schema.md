# MySQL 数据库初始化文档

## 数据库信息

- **数据库名**: `medication_guide`
- **字符集**: `utf8mb4`
- **排序规则**: `utf8mb4_unicode_ci`
- **存储引擎**: InnoDB

---

## 表结构

### 1. drug_document - 药品文档表

存储上传的药品说明书文档元信息。

```sql
CREATE TABLE IF NOT EXISTS `drug_document` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `drug_name` VARCHAR(255) NOT NULL COMMENT '药品名称',
    `file_name` VARCHAR(500) NOT NULL COMMENT '原始文件名',
    `file_path` VARCHAR(1000) NOT NULL COMMENT '文件存储路径',
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='药品文档表';
```

### 2. document_chunk - 文档分块表

存储文档分块后的文本块，用于向量检索。

```sql
CREATE TABLE IF NOT EXISTS `document_chunk` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文档分块表';
```

### 3. query_log - 查询日志表

记录用户查询及LLM回答，用于分析审计。

```sql
CREATE TABLE IF NOT EXISTS `query_log` (
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='查询日志表';
```

---

## 初始化脚本

将以上内容保存为 `init.sql`，执行初始化：

```bash
mysql -u root -p < init.sql
```

或登录后执行：

```sql
SOURCE /path/to/init.sql;
```

---

## 实体与表字段映射

| Entity | Table | 关联关系 |
|--------|-------|---------|
| DrugDocument | drug_document | 主表 |
| DocumentChunk | document_chunk | 关联 drug_document (1:N) |
| QueryLog | query_log | 独立表，无外键 |

---

## 索引说明

| 表名 | 索引名 | 字段 | 用途 |
|------|--------|------|------|
| drug_document | idx_drug_name | drug_name | 按药品名查询 |
| drug_document | idx_status | status | 按状态筛选 |
| drug_document | idx_uploaded_at | uploaded_at | 时间范围查询 |
| document_chunk | idx_document_id | document_id | 关联查询 |
| document_chunk | idx_drug_name | drug_name | 药品检索 |
| document_chunk | idx_section | section | 章节筛选 |
| query_log | idx_session_id | session_id | 会话查询 |
| query_log | idx_user_id | user_id | 用户查询 |
| query_log | idx_created_at | created_at | 时间统计 |
| query_log | idx_hallucination_passed | hallucination_passed | 幻觉统计 |

---

## 与现有JPA配置的差异说明

当前项目使用 `spring.jpa.hibernate.ddl-auto: update`，Hibernate 会自动创建表。本文档提供的手动建表脚本：

1. 显式定义索引，优化查询性能
2. 添加业务含义注释
3. 明确外键约束（级联删除）
4. 支持生产环境数据库版本管理