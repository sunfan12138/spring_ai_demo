package com.ifoodbuy.spring_ai_demo.config;

import com.ifoodbuy.spring_ai_demo.tool.FileSystemViewTool;
import com.ifoodbuy.spring_ai_demo.tool.LoggingToolCallback;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * ChatClient 配置类
 * 配置 ChatClient bean，集成聊天记忆和工具调用功能
 * <p>
 * 根据 Spring AI 文档，使用声明式方式集成工具：
 * 1. 使用 @Tool 注解标记工具方法
 * 2. 使用 ToolCallbacks.from() 从工具类生成 ToolCallback[]
 * 3. 将工具添加到 ChatClient.defaultToolCallbacks()
 */
@Slf4j
@Configuration
public class ChatClientConfig {

    /**
     * 创建 ChatClient bean
     *
     * @param chatModel                     Spring AI 自动配置的 ChatModel
     * @param chatMemory                    聊天记忆实例
     * @param fileSystemViewTool            文件系统查看工具（使用 @Tool 注解）
     * @param toolCallbacksProvider         工具回调提供者（直接注册的 ToolCallback Bean）
     * @param toolCallbackProvidersProvider 工具回调提供者（ToolCallbackProvider，包括 MCP 工具）
     * @return ChatClient 实例
     */
    @Bean
    public ChatClient chatClient(
            ChatModel chatModel,
            ChatMemory chatMemory,
            FileSystemViewTool fileSystemViewTool,
            ObjectProvider<List<ToolCallback>> toolCallbacksProvider,
            ObjectProvider<List<ToolCallbackProvider>> toolCallbackProvidersProvider) {

        // 收集所有工具
        List<ToolCallback> allTools = new ArrayList<>();

        // 1. 从 @Tool 注解的工具类生成 ToolCallback（声明式方式）
        try {
            ToolCallback[] fileSystemTools = ToolCallbacks.from(fileSystemViewTool);
            if (ArrayUtils.isNotEmpty(fileSystemTools)) {
                allTools.addAll(Arrays.asList(fileSystemTools));
                log.info("ChatClient 配置：从 @Tool 注解工具类找到 {} 个工具", fileSystemTools.length);
                for (ToolCallback tool : fileSystemTools) {
                    log.info("  - 工具: {}", tool.getToolDefinition().name());
                }
            }
        } catch (Exception e) {
            log.warn("ChatClient 配置：从 @Tool 注解工具类生成工具时出错: {}", e.getMessage());
        }

        // 2. 收集直接注册的 ToolCallback Bean
        toolCallbacksProvider.ifAvailable(toolCallbacks -> {
            if (CollectionUtils.isNotEmpty(toolCallbacks)) {
                allTools.addAll(toolCallbacks);
                log.info("ChatClient 配置：从 ToolCallback Bean 找到 {} 个工具", toolCallbacks.size());
            }
        });

        // 3. 从 ToolCallbackProvider 收集工具（包括 MCP 工具）
        toolCallbackProvidersProvider.ifAvailable(providers -> {
            if (CollectionUtils.isNotEmpty(providers)) {
                for (ToolCallbackProvider provider : providers) {
                    try {
                        ToolCallback[] toolsArray = provider.getToolCallbacks();
                        if (ArrayUtils.isNotEmpty(toolsArray)) {
                            allTools.addAll(Arrays.asList(toolsArray));
                            log.info("ChatClient 配置：从 ToolCallbackProvider ({}) 找到 {} 个工具",
                                    provider.getClass().getSimpleName(), toolsArray.length);
                        }
                    } catch (Exception e) {
                        log.warn("ChatClient 配置：从 ToolCallbackProvider 获取工具时出错: {}", e.getMessage());
                    }
                }
            }
        });

        // 包装工具回调，记录请求/响应
        if (CollectionUtils.isNotEmpty(allTools)) {
            List<ToolCallback> wrappedTools = new ArrayList<>(allTools.size());
            for (ToolCallback tool : allTools) {
                if (tool instanceof LoggingToolCallback) {
                    wrappedTools.add(tool);
                } else {
                    wrappedTools.add(new LoggingToolCallback(tool));
                }
            }
            allTools.clear();
            allTools.addAll(wrappedTools);
        }

        // 构建 ChatClient
        ChatClient.Builder builder = ChatClient.builder(chatModel)
                // 配置聊天记忆 Advisor
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(chatMemory).build(),
                        new SimpleLoggerAdvisor()
                );

        // 如果有工具，添加到 ChatClient
        if (CollectionUtils.isNotEmpty(allTools)) {
            builder.defaultToolCallbacks(allTools);
            log.info("ChatClient 配置：总共添加 {} 个工具", allTools.size());
            // 打印工具列表
            for (ToolCallback tool : allTools) {
                log.info("  ✓ 工具: {}", tool.getToolDefinition().name());
            }
        } else {
            log.warn("ChatClient 配置：未找到任何工具");
        }

        return builder.defaultSystem("""
                # 角色
                你是一个集联网搜索、文件审计与网页抓取于一体的专业助手。
                
                # 工具调用准则 (严格执行)
                1. **格式规范**：调用工具必须包含所有 `required` 参数。
                   - `search` -> `query` (搜索词)
                   - `executeFileSystemView` -> `operation` (list/read/info), `path` (路径)
                   - `fetch...Article` -> `url` (链接)
                2. **只读限制**：对文件系统仅拥有读取权限，禁止尝试创建、修改或删除。
                3. **调用顺序**：不确定文件名时，先 `list` 确认，再 `read` 读取（限制 <1MB）。
                4. **禁忌**：严禁虚构工具名，严禁在工具调用 JSON 块中包含非代码文字。
                
                # 任务逻辑
                - 外部知识 -> 调用 `search` 搜索。
                - 技术文章 -> 调用 `fetch` 系列工具抓取全文。
                - 本地文件 -> 调用 `executeFileSystemView` 进行审计。
                - 综合输出 -> 结合工具返回的事实，以中文进行简洁准确的回复。
                """).build();
    }
}
