package com.aist.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 代码分析 SSE 事件
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CodeAnalyzeEvent {

    /**
     * 事件类型
     */
    private String type;

    /**
     * 事件数据
     */
    private String data;

    // ==================== 事件类型常量 ====================

    /**
     * 分析开始
     */
    public static final String TYPE_START = "start";

    /**
     * 步骤进度
     */
    public static final String TYPE_STEP = "step";

    /**
     * 工具调用
     */
    public static final String TYPE_TOOL = "tool";

    /**
     * 内容输出（流式文本）
     */
    public static final String TYPE_CONTENT = "content";

    /**
     * 分析完成
     */
    public static final String TYPE_DONE = "done";

    /**
     * 错误
     */
    public static final String TYPE_ERROR = "error";

    /**
     * 澄清问题
     */
    public static final String TYPE_QUESTION = "question";

    // ==================== 静态工厂方法 ====================

    public static CodeAnalyzeEvent start(String message) {
        return new CodeAnalyzeEvent(TYPE_START, message);
    }

    public static CodeAnalyzeEvent step(String message) {
        return new CodeAnalyzeEvent(TYPE_STEP, message);
    }

    public static CodeAnalyzeEvent tool(String message) {
        return new CodeAnalyzeEvent(TYPE_TOOL, message);
    }

    public static CodeAnalyzeEvent content(String text) {
        return new CodeAnalyzeEvent(TYPE_CONTENT, text);
    }

    public static CodeAnalyzeEvent done(String message) {
        return new CodeAnalyzeEvent(TYPE_DONE, message);
    }

    public static CodeAnalyzeEvent error(String message) {
        return new CodeAnalyzeEvent(TYPE_ERROR, message);
    }

    public static CodeAnalyzeEvent question(String jsonData) {
        return new CodeAnalyzeEvent(TYPE_QUESTION, jsonData);
    }
}

