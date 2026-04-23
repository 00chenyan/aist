package com.aist.callback;

/**
 * Callback for code analysis.
 * Used for streaming analysis progress and results.
 */
public interface AnalyzeCallback {

    /**
     * Step progress update.
     *
     * @param step step description
     */
    void onStep(String step);


    /**
     * Content output (streaming LLM text).
     *
     * @param text text content
     */
    void onContent(String text);

    /**
     * Error notification.
     *
     * @param error error message
     */
    void onError(String error);
}
