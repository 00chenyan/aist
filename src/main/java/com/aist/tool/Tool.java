package com.aist.tool;

import com.aist.dto.CodeAnalyzeContextDTO;

import java.util.Collections;
import java.util.List;

/**
 * 工具接口 - 策略模式
 * 每个工具实现此接口，独立处理特定类型的查询
 */
public interface Tool {

    /**
     * 获取工具名称（用于LLM调用）
     *
     * @return 工具名称，如 SEARCH_CONFIG
     */
    String getName();

    /**
     * 获取工具描述（用于生成提示词）
     *
     * @return 工具描述（简短的一句话）
     */
    String getDescription();

    /**
     * 获取参数说明
     *
     * @return 参数说明
     */
    String getParameterDescription();

    /**
     * 获取使用示例（用于生成提示词）
     *
     * @return 使用示例列表，每个示例包含注释说明
     */
    default List<String> getExamples() {
        return Collections.emptyList();
    }

    /**
     * 获取适用场景说明
     *
     * @return 适用场景描述，说明什么时候应该使用这个工具
     */
    default String getUsageScenario() {
        return "";
    }

    /**
     * 获取工具能力说明
     *
     * @return 能力说明（支持什么、不支持什么、特点等）
     */
    default String getCapabilities() {
        return "";
    }

    /**
     * 获取工具优先级（用于排序）
     * 优先级高的工具会在提示词中排在前面
     *
     * @return 优先级，数字越小优先级越高（1-100）
     */
    default int getPriority() {
        return 50; // 默认中等优先级
    }

    /**
     * 执行工具
     *
     * @param request 工具请求
     * @param context 分析上下文
     * @return 工具执行结果
     */
    ToolResult execute(ToolRequest request, CodeAnalyzeContextDTO context);

}

