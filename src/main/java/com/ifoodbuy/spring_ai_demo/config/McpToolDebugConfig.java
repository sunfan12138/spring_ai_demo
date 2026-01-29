package com.ifoodbuy.spring_ai_demo.config;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * MCP 工具调试配置类
 * 用于检查 MCP 工具是否正确注册
 */
@Slf4j
@Component
public class McpToolDebugConfig {

    private final org.springframework.beans.factory.ObjectProvider<List<ToolCallback>> toolCallbacksProvider;
    private final org.springframework.beans.factory.ObjectProvider<List<ToolCallbackProvider>> toolCallbackProviders;
    private final ApplicationContext applicationContext;

    public McpToolDebugConfig(
            org.springframework.beans.factory.ObjectProvider<List<ToolCallback>> toolCallbacksProvider,
            org.springframework.beans.factory.ObjectProvider<List<ToolCallbackProvider>> toolCallbackProviders,
            ApplicationContext applicationContext) {
        this.toolCallbacksProvider = toolCallbacksProvider;
        this.toolCallbackProviders = toolCallbackProviders;
        this.applicationContext = applicationContext;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void debugMcpTools() {
        log.info("=== MCP 工具调试信息 ===");
        
        ToolDebugResult result = new ToolDebugResult();
        
        // 检查 ToolCallback Bean
        debugToolCallbacks(result);
        // 检查 ToolCallbackProvider
        debugToolCallbackProviders(result);
        // 检查 MCP 客户端 Bean
//        debugMcpBeans();
        // 打印总结
        printSummary(result);
        
        log.info("=== MCP 工具调试信息结束 ===");
    }

    /**
     * 检查 ToolCallback Bean
     *
     * @param result 调试结果对象
     */
    private void debugToolCallbacks(ToolDebugResult result) {
        List<ToolCallback> toolCallbacks = toolCallbacksProvider.getIfAvailable();
        if (CollectionUtils.isEmpty(toolCallbacks)) {
            return;
        }
        
        result.foundTools = true;
        result.totalToolCount += toolCallbacks.size();
        log.info("找到 {} 个 ToolCallback Bean", toolCallbacks.size());
        
        for (ToolCallback callback : toolCallbacks) {
            logToolDefinition(callback.getToolDefinition(), "  ✓ ToolCallback");
        }

    }

    /**
     * 检查 ToolCallbackProvider
     *
     * @param result 调试结果对象
     */
    private void debugToolCallbackProviders(ToolDebugResult result) {
        List<ToolCallbackProvider> providers = toolCallbackProviders.getIfAvailable();
        if (CollectionUtils.isEmpty(providers)) {
            return;
        }
        
        log.info("找到 {} 个 ToolCallbackProvider Bean", providers.size());
        
        for (ToolCallbackProvider provider : providers) {
            debugSingleProvider(provider, result);
        }
    }

    /**
     * 调试单个 ToolCallbackProvider
     * 
     * @param provider ToolCallbackProvider 实例
     * @param result 调试结果对象
     */
    private void debugSingleProvider(ToolCallbackProvider provider, ToolDebugResult result) {
        log.info("  - ToolCallbackProvider 类型: {}", provider.getClass().getSimpleName());
        
        try {
            ToolCallback[] toolsArray = provider.getToolCallbacks();
            if (ArrayUtils.isEmpty(toolsArray)) {
                logEmptyProvider();
                return;
            }
            
            log.info("  - ToolCallbackProvider 包含 {} 个工具", toolsArray.length);
            result.foundTools = true;
            result.totalToolCount += toolsArray.length;
            
            for (ToolCallback tool : toolsArray) {
                logToolDefinition(tool.getToolDefinition(), "    ✓ 工具");
            }
        } catch (Exception e) {
            log.error("    ❌ 获取工具时出错: {}", e.getMessage(), e);
        }
    }

    /**
     * 记录空 Provider 的警告信息
     */
    private void logEmptyProvider() {
        log.warn("    ⚠️  该 Provider 没有返回任何工具！");
        log.warn("    可能的原因：");
        log.warn("      1. MCP 服务器连接失败（检查 URL 是否正确）");
        log.warn("      2. MCP 服务器未返回任何工具");
        log.warn("      3. 认证失败（如果需要 token）");
        log.warn("      4. MCP endpoint 已过期或被删除");
    }

    /**
     * 记录工具定义信息
     * 
     * @param toolDef 工具定义
     * @param prefix 日志前缀
     */
    private void logToolDefinition(ToolDefinition toolDef, String prefix) {
        log.info("{}: {}", prefix, toolDef.name());
        log.info("    描述: {}", toolDef.description());
        
        String inputSchema = toolDef.inputSchema();
        if (StringUtils.isNotBlank(inputSchema)) {
            log.info("    参数 Schema: {}", inputSchema);
        } else {
            log.warn("    参数 Schema: 未定义");
        }
    }

    /**
     * 检查 MCP 相关的 Bean
     */
    private void debugMcpBeans() {
        try {
            String[] beanNames = applicationContext.getBeanDefinitionNames();
            log.info("检查所有 Bean 中的 MCP 相关 Bean:");
            
            int mcpBeanCount = 0;
            for (String beanName : beanNames) {
                if (beanName.toLowerCase().contains("mcp")) {
                    mcpBeanCount += logMcpBean(beanName);
                }
            }
            
            if (mcpBeanCount == 0) {
                logMissingMcpBeans();
            }
        } catch (Exception e) {
            log.error("检查 MCP Bean 时出错", e);
        }
    }

    /**
     * 记录单个 MCP Bean 信息
     * 
     * @param beanName Bean 名称
     * @return 如果成功记录返回 1，否则返回 0
     */
    private int logMcpBean(String beanName) {
        try {
            Object bean = applicationContext.getBean(beanName);
            log.info("  - Bean: {} (类型: {})", beanName, bean.getClass().getSimpleName());
            return 1;
        } catch (Exception e) {
            log.warn("  - Bean: {} (获取失败: {})", beanName, e.getMessage());
            return 0;
        }
    }

    /**
     * 记录缺少 MCP Bean 的警告信息
     */
    private void logMissingMcpBeans() {
        log.warn("⚠️  未找到任何 MCP 相关的 Bean");
        log.warn("请检查：");
        log.warn("  1. spring.ai.mcp.client.enabled 是否为 true");
        log.warn("  2. MCP 依赖是否正确添加");
    }

    /**
     * 打印调试总结
     * 
     * @param result 调试结果对象
     */
    private void printSummary(ToolDebugResult result) {
        log.info("=== 工具总结 ===");
        log.info("总共找到 {} 个可用工具", result.totalToolCount);
        
        if (!result.foundTools) {
            log.warn("⚠️  未找到任何可用工具！");
            log.warn("请检查 MCP 服务器配置");
        } else {
            log.info("✓ 工具注册成功，AI 可以调用这些工具");
        }
    }

    /**
     * 工具调试结果内部类
     */
    private static class ToolDebugResult {
        boolean foundTools = false;
        int totalToolCount = 0;
    }
}
