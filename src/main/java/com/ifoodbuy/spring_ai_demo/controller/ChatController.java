package com.ifoodbuy.spring_ai_demo.controller;

import com.ifoodbuy.spring_ai_demo.dto.ChatRequest;
import com.ifoodbuy.spring_ai_demo.dto.ChatResponse;
import com.ifoodbuy.spring_ai_demo.service.ChatService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 聊天控制器
 * 提供 REST API 接口
 */
@Slf4j
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    /**
     * 发送聊天消息（非流式）
     * 
     * @param request 聊天请求
     * @return 聊天响应
     */
    @PostMapping
    public ResponseEntity<ChatResponse> chat(@RequestBody ChatRequest request) {
        // 如果没有提供会话ID，生成一个新的
        if (request.getConversationId() == null || request.getConversationId().isEmpty()) {
            request.setConversationId(UUID.randomUUID().toString());
        }

        try {
            ChatResponse response = chatService.chat(request);
            return ResponseEntity.ok(response);
        } catch (org.springframework.web.reactive.function.client.WebClientResponseException.TooManyRequests e) {
            // 处理 429 限流错误
            return ResponseEntity.status(429)
                    .body(new ChatResponse(
                            "⚠️ API 请求频率过高，请稍后再试。如果持续出现此错误，可能是 API 配额已用完。",
                            request.getConversationId()
                    ));
        } catch (Exception e) {
            // 处理其他错误
            String errorMessage = getErrorMessage(e);
            return ResponseEntity.status(500)
                    .body(new ChatResponse(errorMessage, request.getConversationId()));
        }
    }

    /**
     * 发送聊天消息（流式响应，Server-Sent Events）
     * 
     * @param request 聊天请求
     * @return SSE 流式响应
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestBody ChatRequest request) {
        // 如果没有提供会话ID，生成一个新的
        if (request.getConversationId() == null || request.getConversationId().isEmpty()) {
            request.setConversationId(UUID.randomUUID().toString());
        }

        // 创建 SSE Emitter，设置超时时间为 60 秒
        SseEmitter emitter = new SseEmitter(60000L);
        
        // 获取 Flux 流
        Flux<String> flux = chatService.chatStream(request);
        
        // 异步处理流式响应
        CompletableFuture.runAsync(() -> {
            try {
                // 订阅 Flux 流并发送数据
                flux.subscribe(
                    chunk -> {
                        try {
                            // 发送每个数据块，使用 message 事件名
                            emitter.send(SseEmitter.event()
                                    .name("message")
                                    .data(chunk != null ? chunk : ""));
                        } catch (IOException e) {
                            log.error("发送 SSE 数据时出错", e);
                            emitter.completeWithError(e);
                        }
                    },
                    error -> {
                        // 错误处理
                        log.error("Flux 流错误", error);
                        try {
                            String errorMessage = getErrorMessage(error);
                            emitter.send(SseEmitter.event()
                                    .name("error")
                                    .data(errorMessage));
                            emitter.completeWithError(error);
                        } catch (IOException e) {
                            log.error("发送错误消息到 SSE 时出错", e);
                            emitter.completeWithError(error);
                        }
                    },
                    () -> {
                        try {
                            // 发送完成事件
                            emitter.send(SseEmitter.event()
                                    .name("done")
                                    .data(""));
                            // 完成流
                            emitter.complete();
                        } catch (IOException e) {
                            log.error("完成 SSE 流时出错", e);
                            emitter.completeWithError(e);
                        }
                    }
                );
            } catch (Exception e) {
                log.error("处理流式响应时出错", e);
                emitter.completeWithError(e);
            }
        });

        // 设置完成和错误回调
        emitter.onCompletion(() -> {
            // 流完成时的处理
        });
        
        emitter.onError((ex) -> {
            // 错误处理
            log.error("SSE Emitter 发生错误", ex);
        });

        // 超时处理
        emitter.onTimeout(emitter::complete);

        return emitter;
    }
    
    /**
     * 获取友好的错误消息
     */
    private String getErrorMessage(Throwable error) {
        String message = error.getMessage();
        
        if (message == null) {
            return "错误: " + error.getClass().getSimpleName();
        }

        // 处理 429 限流错误
        if (message.contains("429")) {
            return "⚠️ API 请求频率过高，请稍后再试。如果持续出现此错误，可能是 API 配额已用完。";
        }

        // 处理 401 认证错误
        if (message.contains("401")) {
            return "⚠️ API 密钥无效，请检查配置。";
        }

        // 处理 500 服务器错误
        if (message.contains("500")) {
            return "⚠️ 服务器内部错误，请稍后重试。";
        }

        // 默认错误消息
        return "错误: " + message;
    }
}
