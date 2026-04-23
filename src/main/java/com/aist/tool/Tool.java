package com.aist.tool;

import com.aist.dto.CodeAnalyzeContextDTO;

import java.util.Collections;
import java.util.List;

/**
 * Tool interface (strategy pattern).
 * Each tool implements this interface and handles a specific kind of query.
 */
public interface Tool {

    /**
     * Tool name (used for LLM tool calls).
     *
     * @return tool name, e.g. SEARCH_CONFIG
     */
    String getName();

    /**
     * Short description (used in prompts).
     *
     * @return one-line description
     */
    String getDescription();

    /**
     * Parameter specification.
     *
     * @return parameter description
     */
    String getParameterDescription();

    /**
     * Usage examples (for prompts).
     *
     * @return examples with brief notes
     */
    default List<String> getExamples() {
        return Collections.emptyList();
    }

    /**
     * When to use this tool.
     *
     * @return scenario description
     */
    default String getUsageScenario() {
        return "";
    }

    /**
     * Capabilities and limitations.
     *
     * @return what is supported, not supported, and notable behavior
     */
    default String getCapabilities() {
        return "";
    }

    /**
     * Sort order in prompts (lower comes first).
     *
     * @return priority (1–100); default medium
     */
    default int getPriority() {
        return 50; // default medium priority
    }

    /**
     * Executes the tool.
     *
     * @param request tool request
     * @param context analysis context
     * @return execution result
     */
    ToolResult execute(ToolRequest request, CodeAnalyzeContextDTO context);

}
