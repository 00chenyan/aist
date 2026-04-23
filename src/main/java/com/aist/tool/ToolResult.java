package com.aist.tool;

import lombok.Data;

/**
 * Tool execution result.
 */
@Data
public class ToolResult {

    /**
     * Tool name.
     */
    private String toolName;

    /**
     * Argument string.
     */
    private String arguments;

    /**
     * Result payload.
     */
    private String result;

    /**
     * Whether execution succeeded.
     */
    private boolean success;

    /**
     * Error message when failed.
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
     * Builds a successful result.
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
     * Builds a successful "not found" style result.
     */
    public static ToolResult notFound(String toolName, String arguments, String message) {
        return success(toolName, arguments, message);
    }

    /**
     * Builds a failed result.
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
     * Short summary for logging.
     */
    public String getSummary() {
        if (result == null) {
            return "No result";
        }
        return result.length() > 100 ? result.substring(0, 100) + "..." : result;
    }
}
