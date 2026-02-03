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
                // 默认系统提示：引导使用搜索、抓取网页、文件系统等工具
                .defaultSystem("""
                    你可使用的工具包括：
                    - bing_search：必应搜索，获取标题、链接和摘要。
                    - crawl_webpage：根据搜索结果的 uuids 和 urlMap 抓取网页正文内容。
                    - executeFileSystemView：文件系统查看（只读），可列出目录内容(list)、读取文件内容(read)、获取文件/目录信息(info)；需传入 operation 与 path，不包含创建、删除、修改。
                    
                    当用户询问需要具体数据的问题（如天气、股价、新闻详情等）时：
                    1. 先用 bing_search 获取搜索结果。
                    2. 若搜索结果里只有标题、链接和简短摘要，没有用户要的具体信息（如「今天多少度」「具体数值」），则必须再调用 crawl_webpage，传入搜索结果中的 uuids 和 urlMap，抓取对应网页的正文内容。
                    3. 根据抓取到的页面内容提取并整理出用户关心的具体信息，在回复中直接写出（如气温、天气状况、风力等），不要只罗列链接并让用户自己去点。
                    4. 只有在无法抓取或页面无相关内容时，才退而求其次说明「可点击某链接查看」。
                    
                    当用户询问查看某路径下的文件、读取某文件内容或查看文件/目录信息时，使用 executeFileSystemView，按需选择 operation（list/read/info）和 path 进行只读查看。
                    """)
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

        return builder.build();
    }
}
