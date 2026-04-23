package com.aist.tool;

import lombok.Data;

/**
 * 工具执行结果
 */
@Data
public class ToolResult {

    /**
     * 工具名称
     */
    private String toolName;

    /**
     * 参数字符串
     */
    private String arguments;

    /**
     * 执行结果
     */
    private String result;

    /**
     * 是否成功
     */
    private boolean success;

    /**
     * 错误信息（如果失败）
     */
    private String errorMessage;

    public ToolResult() {
    }

    public ToolResult(String toolName, String arguments, String result) {
        this.toolName = toolName;
        this.arguments = arguments;
        this.result = result;
        this.success = true;
    }

    /**
     * 创建成功结果
     */
    public static ToolResult success(String toolName, String arguments, String result) {
        ToolResult tr = new ToolResult();
        tr.setToolName(toolName);
        tr.setArguments(arguments);
        tr.setResult(result);
        tr.setSuccess(true);
        return tr;
    }

    /**
     * 创建未找到结果
     */
    public static ToolResult notFound(String toolName, String arguments, String message) {
        return success(toolName, arguments, message);
    }

    /**
     * 创建错误结果
     */
    public static ToolResult error(String toolName, String arguments, String errorMessage) {
        ToolResult tr = new ToolResult();
        tr.setToolName(toolName);
        tr.setArguments(arguments);
        tr.setResult("执行失败: " + errorMessage);
        tr.setSuccess(false);
        tr.setErrorMessage(errorMessage);
        return tr;
    }

    /**
     * 获取结果摘要（用于日志）
     */
    public String getSummary() {
        if (result == null) {
            return "无结果";
        }
        return result.length() > 100 ? result.substring(0, 100) + "..." : result;
    }
}

