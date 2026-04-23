package com.aist.tool;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 工具请求
 */
@Data
public class ToolRequest {

    /**
     * 工具名称
     */
    private String toolName;

    /**
     * 参数列表
     */
    private List<String> arguments = new ArrayList<>();

    public ToolRequest() {
    }

    public ToolRequest(String toolName, List<String> arguments) {
        this.toolName = toolName;
        this.arguments = arguments != null ? arguments : new ArrayList<>();
    }

    /**
     * 获取参数字符串（用于日志）
     *
     * @return 参数字符串
     */
    public String getArgumentsString() {
        return String.join(", ", arguments);
    }

    /**
     * 获取第一个参数
     *
     * @return 第一个参数，如果没有则返回空字符串
     */
    public String getFirstArgument() {
        return arguments.isEmpty() ? "" : arguments.get(0);
    }

    /**
     * 判断是否有参数
     *
     * @return 是否有参数
     */
    public boolean hasArguments() {
        return !arguments.isEmpty();
    }
}

