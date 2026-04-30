# 药品说明书智能问答系统

基于 RAG (Retrieval-Augmented Generation) 架构的药品说明书智能问答系统，**核心约束：所有回答严格基于药品说明书文档，零幻觉**。

## 🏗️ 系统架构

```
┌─────────────┐     ┌──────────────┐     ┌─────────────────┐     ┌──────────────┐
│  客户端      │────▶│  API 网关层   │────▶│  RAG 检索增强层  │────▶│  本地大模型   │
│             │◀────│ (Spring Boot) │◀────│  (向量检索+重排)  │◀────│ (Ollama/vLLM)│
└─────────────┘     └──────────────┘     └─────────────────┘     └──────────────┘
```

## 🛠️ 技术栈

| 层级 | 技术 | 说明 |
|------|------|------|
| API 层 | Spring Boot 3.2 | RESTful API |
| RAG 检索 | Spring AI + LangChain4j | 检索增强生成 |
| 向量数据库 | Milvus | 药品说明书向量存储 |
| Embedding | BGE-large-zh | 中文向量化 |
| 大语言模型 | Ollama (Qwen2.5) | 本地部署 |
| 缓存 | Redis | 热点问答缓存 |
| 结构存储 | MySQL | 药品元数据、日志 |

## 🚀 快速开始

### 方式一：Docker Compose（推荐开发测试）

```bash
# 克隆项目
git clone https://github.com/nirvanafire/medication-guide.git
cd medication-guide

# 启动所有服务
docker-compose up -d

# 等待服务启动后，拉取 Ollama 模型
docker exec medication-guide-ollama ollama pull qwen2.5:7b
docker exec medication-guide-ollama ollama pull bge-large-zh-v1.5

# 初始化脚本
chmod +x docker/init.sh
./docker/init.sh
```

### 方式二：Kubernetes（推荐生产部署）

```bash
# 创建命名空间
kubectl apply -f k8s/namespace.yaml

# 部署基础设施
kubectl apply -f k8s/configmap.yaml
kubectl apply -f k8s/secret.yaml
kubectl apply -f k8s/mysql.yaml
kubectl apply -f k8s/redis.yaml
kubectl apply -f k8s/milvus-statefulset.yaml
kubectl apply -f k8s/gpu-deployment.yaml

# 部署 API 服务
kubectl apply -f k8s/api-deployment.yaml

# 配置 Ingress
kubectl apply -f k8s/ingress.yaml

# 验证部署状态
kubectl get pods -n drug-qa
```

## 📡 API 接口

### 1. 药品问答

```bash
POST /api/v1/drug-qa/query
Content-Type: application/json

{
    "question": "阿莫西林儿童用量是多少？",
    "drug_name": "阿莫西林胶囊",
    "top_k": 3
}
```

**响应：**
```json
{
    "code": 0,
    "data": {
        "answer": "根据药品说明书，儿童用量：每日20-40mg/kg...",
        "sources": [{"section": "用法用量", "score": 0.92}],
        "hallucination_check": {"passed": true}
    }
}
```

### 2. 流式问答 (SSE)

```bash
POST /api/v1/drug-qa/query/stream
Accept: text/event-stream
```

### 3. 文档管理

```bash
# 上传药品说明书
POST /api/v1/drug-documents/upload
Content-Type: multipart/form-data

# 查看文档列表
GET /api/v1/drug-documents

# 删除文档
DELETE /api/v1/drug-documents/{id}
```

## 🔒 幻觉防护

系统采用三重保障机制：

1. **来源校验**：回答每句话是否可在检索片段找到依据
2. **数字校验**：剂量、百分比等数字是否与原文一致
3. **置信度评估**：模型输出置信度阈值检测

## 📊 监控指标

| 指标 | 告警阈值 | 说明 |
|------|----------|------|
| 响应时间 P99 | > 5s | 排查瓶颈 |
| 幻觉通过率 | < 90% | Prompt质量下降 |
| 缓存命中率 | < 30% | 评估缓存策略 |

## 📁 项目结构

```
medication-guide/
├── src/main/java/com/medication/
│   ├── config/          # 配置类
│   ├── controller/      # API 控制器
│   ├── service/         # 业务服务
│   ├── repository/      # 数据访问
│   ├── entity/          # 实体类
│   ├── dto/             # 数据传输对象
│   └── util/            # 工具类
├── src/main/resources/
│   └── application.yml  # 应用配置
├── k8s/                 # Kubernetes 部署文件
├── docker/              # Docker 相关文件
├── Dockerfile
├── docker-compose.yml
└── pom.xml
```

## ⚠️ 注意事项

### 医疗合规
- 响应必须包含免责声明
- 支持文档版本追踪
- 只收录药监局批准的说明书

### 准确性保障
- LLM temperature = 0.1（极低随机性）
- 相似度阈值 0.6，低于则返回"未找到"
- 关键数字必须与原文完全一致

### 安全防护
- 防 Prompt 注入
- API 限流
- 租户数据隔离

## 📄 License

MIT License