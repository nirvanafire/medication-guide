package com.medication.config;

import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.milvus.MilvusVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MilvusConfig {

    @Value("${spring.ai.vectorstore.milvus.host:192.168.31.132}")
    private String host;

    @Value("${spring.ai.vectorstore.milvus.port:19530}")
    private int port;

    @Value("${spring.ai.vectorstore.milvus.collection-name:drug_documents}")
    private String collectionName;

    @Value("${spring.ai.vectorstore.milvus.embedding-dimension:1024}")
    private int embeddingDimension;

    @Value("${spring.ai.vectorstore.milvus.index-type:IVF_FLAT}")
    private String indexType;

    @Value("${spring.ai.vectorstore.milvus.metric-type:COSINE}")
    private String metricType;

    @Bean
    public MilvusServiceClient milvusServiceClient() {
        return new MilvusServiceClient(ConnectParam.newBuilder()
                .withHost(host)
                .withPort(port)
                .build());
    }

    @Bean
    public VectorStore vectorStore(MilvusServiceClient milvusServiceClient, EmbeddingModel embeddingModel) {
        return MilvusVectorStore.builder(milvusServiceClient, embeddingModel)
                .collectionName(collectionName)
                .embeddingDimension(embeddingDimension)
                .indexType(IndexType.valueOf(indexType))
                .metricType(MetricType.valueOf(metricType))
                .build();
    }
}
