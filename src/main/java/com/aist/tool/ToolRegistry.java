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
 * Tool registry (factory pattern).
 * Registers and resolves tools.
 */
@Slf4j
@Component
public class ToolRegistry {

    /**
     * Tool map by upper-case name.
     */
    private final Map<String, Tool> tools = new ConcurrentHashMap<>();

    /**
     * All {@link Tool} beans.
     */
    private final List<Tool> toolList;

    public ToolRegistry(List<Tool> toolList) {
        this.toolList = toolList;
    }

    /**
     * Auto-registers all tools on startup.
     */
    @PostConstruct
    public void init() {
        for (Tool tool : toolList) {
            register(tool);
        }
        log.info("Tool registry initialized, {} tools registered", tools.size());
    }

    /**
     * Registers a tool.
     *
     * @param tool tool instance
     */
    public void register(Tool tool) {
        String name = tool.getName().toUpperCase();
        tools.put(name, tool);
        log.debug("Registered tool: {}", name);
    }

    /**
     * Looks up a tool by name.
     *
     * @param name tool name
     * @return tool or null
     */
    public Tool getTool(String name) {
        if (name == null) {
            return null;
        }
        return tools.get(name.toUpperCase());
    }

    /**
     * Builds prompt text describing all tools.
     *
     * @return formatted descriptions
     */
    public String getAllToolDescriptions() {
        StringBuilder sb = new StringBuilder();

        // Sort by priority
        List<Tool> sortedTools = tools.values().stream()
                .sorted(Comparator.comparingInt(Tool::getPriority))
                .toList();

        int index = 1;
        for (Tool tool : sortedTools) {
            sb.append(index++).append(". **").append(tool.getName()).append("** - ")
                    .append(tool.getDescription()).append("\n");
            sb.append("   格式: [TOOL_CALL:").append(tool.getName()).append(":")
                    .append(tool.getParameterDescription()).append("]\n");

            if (!tool.getUsageScenario().isEmpty()) {
                sb.append("   场景: ").append(tool.getUsageScenario()).append("\n");
            }

            if (!tool.getCapabilities().isEmpty()) {
                String capabilities = tool.getCapabilities().trim();
                String[] lines = capabilities.split("\n");
                for (String line : lines) {
                    if (!line.trim().isEmpty()) {
                        sb.append("   ").append(line.trim()).append("\n");
                    }
                }
            }

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
     * Executes a single tool.
     *
     * @param request tool request
     * @param context analysis context
     * @return result
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
            log.error("Tool execution failed: {} - {}", request.getToolName(), e.getMessage(), e);
            return ToolResult.error(request.getToolName(), request.getArgumentsString(), e.getMessage());
        }
    }
}

