#!/bin/bash

# 药品说明书智能问答系统 - 初始化脚本

echo "=== 药品说明书智能问答系统 初始化 ==="

# 1. 拉取 Ollama 模型
echo "正在拉取 Ollama 模型..."
docker exec medication-guide-ollama ollama pull qwen2.5:7b
docker exec medication-guide-ollama ollama pull bge-large-zh-v1.5

echo "模型拉取完成!"

# 2. 初始化 Milvus
echo "正在初始化 Milvus 向量库..."

# 3. 创建数据库表
echo "MySQL 数据库表将在应用启动时自动创建..."

# 4. 健康检查
echo "等待服务启动..."
sleep 10

echo "检查服务状态..."
curl -s http://localhost:8080/api/v1/health || echo "API 尚未就绪，请稍后再试"

echo ""
echo "=== 初始化完成 ==="
echo ""
echo "访问地址:"
echo "  - API: http://localhost:8080"
echo "  - 问答接口: POST http://localhost:8080/api/v1/drug-qa/query"
echo "  - 文档上传: POST http://localhost:8080/api/v1/drug-documents/upload"
echo ""
echo "Ollama 模型管理:"
echo "  - 查看模型: docker exec medication-guide-ollama ollama list"
echo "  - 添加模型: docker exec medication-guide-ollama ollama pull <model-name>"