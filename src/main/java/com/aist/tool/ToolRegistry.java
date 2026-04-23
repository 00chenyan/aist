package com.aist.tool;

import com.aist.dto.CodeAnalyzeContextDTO;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具注册中心 - 工厂模式
 * 管理所有工具的注册和查找
 */
@Slf4j
@Component
public class ToolRegistry {

    /**
     * 工具映射表
     */
    private final Map<String, Tool> tools = new ConcurrentHashMap<>();

    /**
     * 注入所有Tool实现
     */
    private final List<Tool> toolList;

    public ToolRegistry(List<Tool> toolList) {
        this.toolList = toolList;
    }

    /**
     * 初始化时自动注册所有工具
     */
    @PostConstruct
    public void init() {
        for (Tool tool : toolList) {
            register(tool);
        }
        log.info("工具注册中心初始化完成，共注册 {} 个工具", tools.size());
    }

    /**
     * 注册工具
     *
     * @param tool 工具实例
     */
    public void register(Tool tool) {
        String name = tool.getName().toUpperCase();
        tools.put(name, tool);
        log.debug("注册工具: {}", name);
    }

    /**
     * 获取工具
     *
     * @param name 工具名称
     * @return 工具实例，如果不存在返回null
     */
    public Tool getTool(String name) {
        if (name == null) {
            return null;
        }
        return tools.get(name.toUpperCase());
    }

    /**
     * 获取所有工具描述（用于生成提示词）
     *
     * @return 工具描述文本
     */
    public String getAllToolDescriptions() {
        StringBuilder sb = new StringBuilder();

        // 按优先级排序工具
        List<Tool> sortedTools = tools.values().stream()
                .sorted(Comparator.comparingInt(Tool::getPriority))
                .toList();

        int index = 1;
        for (Tool tool : sortedTools) {
            sb.append(index++).append(". **").append(tool.getName()).append("** - ")
                    .append(tool.getDescription()).append("\n");
            sb.append("   格式: [TOOL_CALL:").append(tool.getName()).append(":")
                    .append(tool.getParameterDescription()).append("]\n");

            // 添加适用场景
            if (!tool.getUsageScenario().isEmpty()) {
                sb.append("   场景: ").append(tool.getUsageScenario()).append("\n");
            }

            // 添加能力说明
            if (!tool.getCapabilities().isEmpty()) {
                String capabilities = tool.getCapabilities().trim();
                // 将多行能力说明缩进
                String[] lines = capabilities.split("\n");
                for (String line : lines) {
                    if (!line.trim().isEmpty()) {
                        sb.append("   ").append(line.trim()).append("\n");
                    }
                }
            }

            // 添加使用示例
            if (!tool.getExamples().isEmpty()) {
                sb.append("   示例:\n");
                for (String example : tool.getExamples()) {
                    sb.append("     ").append(example).append("\n");
                }
            }

            sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * 执行单个工具
     *
     * @param request 工具请求
     * @param context 分析上下文
     * @return 执行结果
     */
    public ToolResult executeTool(ToolRequest request, CodeAnalyzeContextDTO context) {
        if (request == null || request.getToolName() == null) {
            return ToolResult.error("UNKNOWN", "", "工具请求为空");
        }

        Tool tool = getTool(request.getToolName());
        if (tool == null) {
            return ToolResult.error(request.getToolName(), request.getArgumentsString(),
                    "未知的工具: " + request.getToolName());
        }

        try {
            return tool.execute(request, context);
        } catch (Exception e) {
            log.error("工具执行失败: {} - {}", request.getToolName(), e.getMessage(), e);
            return ToolResult.error(request.getToolName(), request.getArgumentsString(), e.getMessage());
        }
    }
}

