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
 * Code analysis context.
 * Carries data between pipeline stages.
 */
@Data
public class CodeAnalyzeContextDTO {

    // ==================== Input ====================

    /**
     * Original request.
     */
    private CodeAnalyzeRequest request;

    /**
     * Project name.
     */
    private String projectName;

    /**
     * Project code (unique identifier).
     */
    private String projectCode;

    /**
     * Git repository URL.
     */
    private String gitRepoUrl;

    /**
     * Project source path.
     */
    private String projectPath;

    /**
     * DeepSeek API key.
     */
    private String deepseekApiKey;

    // ==================== Parse results ====================

    /**
     * All parsed methods.
     */
    private List<MethodInfo> allMethods = new ArrayList<>();

    /**
     * Method map (method signature -> method info).
     */
    private Map<String, MethodInfo> methodMap = new HashMap<>();

    /**
     * Class to base path map (class name -> RequestMapping base path).
     */
    private Map<String, String> classToBasePath = new HashMap<>();

    // ==================== LLM conversation ====================

    /**
     * Conversation history.
     */
    private List<DeepSeekUtil.Message> conversationHistory = new ArrayList<>();

    /**
     * Final answer text.
     */
    private String finalAnswer;

    /**
     * Non-streaming mode: when true, do not write events to SseEmitter (emitter may be null).
     */
    private boolean blockingMode;

    /**
     * When the model returns a clarification question, JSON is stored here (shared by streaming and blocking;
     * used after completion to detect early termination).
     */
    private String clarificationQuestionJson;

    // ==================== Callbacks and tools ====================

    /**
     * Analysis callback.
     */
    private AnalyzeCallback callback;

    /**
     * SSE emitter (for sending events).
     */
    private Object emitter;  // Object to avoid circular dependency

    /**
     * Tool registry.
     */
    private ToolRegistry toolRegistry;


    // ==================== Database ====================

    /**
     * Database name.
     */
    private String databaseName;

    /**
     * Data source name.
     */
    private String dbSourceName;

    // ==================== Vector store ====================

    /**
     * Vector collection name.
     */
    private String vectorCollectionName;

    // ==================== Helpers ====================

    /**
     * Returns the API URL from the request.
     */
    public String getApiUrl() {
        return request != null ? request.getApiUrl() : null;
    }

    /**
     * Returns the question from the request.
     */
    public String getQuestion() {
        return request != null ? request.getQuestion() : null;
    }

    /**
     * Notifies step progress.
     */
    public void notifyStep(String step) {
        if (callback != null) {
            callback.onStep(step);
        }
    }


    /**
     * Notifies content output.
     */
    public void notifyContent(String content) {
        if (callback != null) {
            callback.onContent(content);
        }
    }

    /**
     * Notifies an error.
     */
    public void notifyError(String error) {
        if (callback != null) {
            callback.onError(error);
        }
    }
}
