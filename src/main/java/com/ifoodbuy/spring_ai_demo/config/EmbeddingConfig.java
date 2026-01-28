package com.ifoodbuy.spring_ai_demo.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.transformers.TransformersEmbeddingModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Embedding 模型配置
 * 使用本地 ONNX 模型 (Transformers) 替代 OpenAI Embedding
 */
@Configuration
public class EmbeddingConfig {

    @Bean
    public EmbeddingModel embeddingModel() {
        // 默认使用 all-MiniLM-L6-v2 模型
        // 第一次运行时会下载模型文件到缓存目录
        TransformersEmbeddingModel embeddingModel = new TransformersEmbeddingModel();
        
        // 显式调用初始化
        try {
            embeddingModel.afterPropertiesSet();
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize TransformersEmbeddingModel", e);
        }
        
        return embeddingModel;
    }
}
