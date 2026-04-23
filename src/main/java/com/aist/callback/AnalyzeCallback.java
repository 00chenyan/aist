package com.aist.callback;

/**
 * 代码分析回调接口
 * 用于流式输出分析过程和结果
 */
public interface AnalyzeCallback {

    /**
     * 步骤进度
     *
     * @param step 步骤描述
     */
    void onStep(String step);


    /**
     * 内容输出（LLM 流式文本）
     *
     * @param text 文本内容
     */
    void onContent(String text);

    /**
     * 发生错误
     *
     * @param error 错误信息
     */
    void onError(String error);
}

