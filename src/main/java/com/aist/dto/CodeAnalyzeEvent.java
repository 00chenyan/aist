package com.aist.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Server-Sent Event payload for code analysis.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CodeAnalyzeEvent {

    /**
     * Event type.
     */
    private String type;

    /**
     * Event payload.
     */
    private String data;

    // ==================== Event type constants ====================

    /**
     * Analysis started.
     */
    public static final String TYPE_START = "start";

    /**
     * Step progress.
     */
    public static final String TYPE_STEP = "step";

    /**
     * Tool invocation.
     */
    public static final String TYPE_TOOL = "tool";

    /**
     * Content output (streaming text).
     */
    public static final String TYPE_CONTENT = "content";

    /**
     * Analysis finished.
     */
    public static final String TYPE_DONE = "done";

    /**
     * Error.
     */
    public static final String TYPE_ERROR = "error";

    /**
     * Clarification question.
     */
    public static final String TYPE_QUESTION = "question";

    // ==================== Factory methods ====================

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
