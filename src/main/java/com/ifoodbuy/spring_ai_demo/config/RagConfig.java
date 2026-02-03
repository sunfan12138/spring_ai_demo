package com.ifoodbuy.spring_ai_demo.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * RAG 混合检索与重排配置，绑定 app.rag.*
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.rag")
public class RagConfig {

    private final Hybrid hybrid = new Hybrid();
    private final Rerank rerank = new Rerank();

    @Data
    public static class Hybrid {
        /** 向量检索权重 */
        private double vectorWeight = 0.7;
        /** BM25 关键词检索权重 */
        private double bm25Weight = 0.3;
    }

    @Data
    public static class Rerank {
        /** 先召回 topK * 此倍数，再融合重排后取 topK */
        private int candidateMultiplier = 3;
    }
}
