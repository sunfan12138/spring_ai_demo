package com.ifoodbuy.spring_ai_demo.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.lang.Nullable;

/**
 * 记录工具调用请求/响应的包装器。
 */
@Slf4j
public class LoggingToolCallback implements ToolCallback {

    private static final int DEFAULT_MAX_LOG_LENGTH = 4000;

    private final ToolCallback delegate;
    private final int maxLogLength;

    public LoggingToolCallback(ToolCallback delegate) {
        this(delegate, DEFAULT_MAX_LOG_LENGTH);
    }

    public LoggingToolCallback(ToolCallback delegate, int maxLogLength) {
        this.delegate = delegate;
        this.maxLogLength = Math.max(512, maxLogLength);
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public String call(String toolInput) {
        String toolName = delegate.getToolDefinition().name();
        log.debug("工具调用请求: tool={}, body={}", toolName, truncate(toolInput));
        try {
            String response = delegate.call(toolInput);
            log.debug("工具调用响应: tool={}, body={}", toolName, truncate(response));
            return response;
        } catch (Exception ex) {
            log.error("工具调用失败: tool={}, error={}", toolName, ex.getMessage(), ex);
            throw ex;
        }
    }

    @Override
    public String call(String toolInput, @Nullable ToolContext toolContext) {
        String toolName = delegate.getToolDefinition().name();
        if (toolContext != null && toolContext.getContext() != null && !toolContext.getContext().isEmpty()) {
            log.debug("工具调用上下文: tool={}, context={}", toolName, toolContext.getContext());
        }
        log.debug("工具调用请求: tool={}, body={}", toolName, truncate(toolInput));
        try {
            String response = delegate.call(toolInput, toolContext);
            log.debug("工具调用响应: tool={}, body={}", toolName, truncate(response));
            return response;
        } catch (Exception ex) {
            log.error("工具调用失败: tool={}, error={}", toolName, ex.getMessage(), ex);
            throw ex;
        }
    }

    private String truncate(String value) {
        if (value == null) {
            return "null";
        }
        if (value.length() <= maxLogLength) {
            return value;
        }
        return value.substring(0, maxLogLength) + "...(truncated)";
    }
}
