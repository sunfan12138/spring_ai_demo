package com.ifoodbuy.spring_ai_demo.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.transformers.TransformersEmbeddingModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Embedding 模型配置
 * 使用本地 ONNX 模型 (Transformers) 替代 OpenAI Embedding
 * 使用 resources 下的 BGE 中文模型: bge-small-zh-v1.5
 */
@Configuration
public class EmbeddingConfig {

    private static final String BGE_MODEL_BASE = "classpath:/onnx/bge-small-zh-v1.5/";

    @Bean
    public EmbeddingModel embeddingModel() {
        TransformersEmbeddingModel embeddingModel = new TransformersEmbeddingModel();
        // 使用 resources 下的 BGE 模型
        embeddingModel.setModelResource(BGE_MODEL_BASE + "model_quantized.onnx");
        embeddingModel.setTokenizerResource(BGE_MODEL_BASE + "tokenizer.json");
        // Spring AI 内部将 ONNX 输出强转为 float[][][]（3D: batch, seq, dim）并做 mean pooling，
        // 故必须使用 3D 输出 token_embeddings，不能使用 2D 的 sentence_embedding（会触发 [[F 转 [[[F 的 ClassCastException）
        embeddingModel.setModelOutputName("token_embeddings");
        // 避免 ONNX ragged array 错误
        embeddingModel.setTokenizerOptions(java.util.Map.of("padding", "true"));

        try {
            embeddingModel.afterPropertiesSet();
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize TransformersEmbeddingModel", e);
        }

        return embeddingModel;
    }
}
