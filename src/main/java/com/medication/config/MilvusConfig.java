package com.medication.config;

import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.milvus.MilvusVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class MilvusConfig {

    @Value("${spring.ai.vectorstore.milvus.host:192.168.31.72}")
    private String host;

    @Value("${spring.ai.vectorstore.milvus.port:19530}")
    private int port;

    @Value("${spring.ai.vectorstore.milvus.database-name:medication_guide}")
    private String databaseName;

    @Value("${spring.ai.vectorstore.milvus.collection-name:drug_documents}")
    private String collectionName;

    @Value("${spring.ai.vectorstore.milvus.embedding-dimension:1024}")
    private int embeddingDimension;

    @Value("${spring.ai.vectorstore.milvus.index-type:IVF_FLAT}")
    private String indexType;

    @Value("${spring.ai.vectorstore.milvus.metric-type:COSINE}")
    private String metricType;

    @Value("${spring.ai.vectorstore.milvus.initialize-schema:true}")
    private boolean initializeSchema;

    @Bean
    public MilvusServiceClient milvusServiceClient() {
        log.info("连接Milvus服务: host={}, port={}", host, port);
        return new MilvusServiceClient(ConnectParam.newBuilder()
                .withHost(host)
                .withPort(port)
                .build());
    }

    @Bean
    public MilvusVectorStore vectorStore(MilvusServiceClient milvusServiceClient, EmbeddingModel embeddingModel) {
        log.info("初始化MilvusVectorStore: host={}, port={}, database={}, collection={}, dimension={}, initializeSchema={}",
                host, port, databaseName, collectionName, embeddingDimension, initializeSchema);

        MilvusVectorStore.Builder builder = MilvusVectorStore.builder(milvusServiceClient, embeddingModel)
                .databaseName(databaseName)
                .collectionName(collectionName)
                .embeddingDimension(embeddingDimension)
                .indexType(IndexType.valueOf(indexType))
                .metricType(MetricType.valueOf(metricType))
                .initializeSchema(initializeSchema);

        return builder.build();
    }
}