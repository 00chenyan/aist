package com.aist.dto;

import com.aist.callback.AnalyzeCallback;
import com.aist.tool.ToolRegistry;
import com.aist.util.DeepSeekUtil;
import lombok.Data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 代码分析上下文
 * 在各阶段间传递数据
 */
@Data
public class CodeAnalyzeContextDTO {

    // ==================== 输入参数 ====================

    /**
     * 原始请求
     */
    private CodeAnalyzeRequest request;

    /**
     * 项目名称
     */
    private String projectName;

    /**
     * 项目代码（唯一标识）
     */
    private String projectCode;

    /**
     * Git仓库URL
     */
    private String gitRepoUrl;

    /**
     * 项目代码路径
     */
    private String projectPath;

    /**
     * DeepSeek API Key
     */
    private String deepseekApiKey;

    // ==================== 解析结果 ====================

    /**
     * 所有解析的方法
     */
    private List<MethodInfo> allMethods = new ArrayList<>();

    /**
     * 方法映射表（方法签名 -> 方法信息）
     */
    private Map<String, MethodInfo> methodMap = new HashMap<>();

    /**
     * 类到基础路径映射（类名 -> RequestMapping路径）
     */
    private Map<String, String> classToBasePath = new HashMap<>();

    // ==================== LLM对话 ====================

    /**
     * 对话历史
     */
    private List<DeepSeekUtil.Message> conversationHistory = new ArrayList<>();

    /**
     * 最终答案
     */
    private String finalAnswer;

    /**
     * 非流式接口：为 true 时不向 SseEmitter 写事件（emitter 可为 null）
     */
    private boolean blockingMode;

    /**
     * 模型返回澄清问题时暂存 JSON（流式与非流式共用，用于结束后判断是否已提前结束）
     */
    private String clarificationQuestionJson;

    // ==================== 回调和工具 ====================

    /**
     * 分析回调
     */
    private AnalyzeCallback callback;

    /**
     * SSE 发射器（用于发送事件）
     */
    private Object emitter;  // 使用 Object 类型避免循环依赖

    /**
     * 工具注册中心
     */
    private ToolRegistry toolRegistry;


    // ==================== 数据库配置 ====================

    /**
     * 数据库名称
     */
    private String databaseName;

    /**
     * 数据源名称
     */
    private String dbSourceName;

    // ==================== 向量配置 ====================

    /**
     * 向量集合名称
     */
    private String vectorCollectionName;

    // ==================== 便捷方法 ====================

    /**
     * 获取接口URL
     */
    public String getApiUrl() {
        return request != null ? request.getApiUrl() : null;
    }

    /**
     * 获取问题描述
     */
    public String getQuestion() {
        return request != null ? request.getQuestion() : null;
    }

    /**
     * 通知步骤进度
     */
    public void notifyStep(String step) {
        if (callback != null) {
            callback.onStep(step);
        }
    }

//    /**
//     * 通知工具调用
//     */
//    public void notifyToolCall(String toolName, String args) {
//        if (callback != null) {
//            callback.onToolCall(toolName, args);
//        }
//    }

//    /**
//     * 通知工具结果
//     */
//    public void notifyToolResult(String toolName, String result) {
//        if (callback != null) {
//            callback.onToolResult(toolName, result);
//        }
//    }

    /**
     * 通知内容输出
     */
    public void notifyContent(String content) {
        if (callback != null) {
            callback.onContent(content);
        }
    }

    /**
     * 通知错误
     */
    public void notifyError(String error) {
        if (callback != null) {
            callback.onError(error);
        }
    }
}

