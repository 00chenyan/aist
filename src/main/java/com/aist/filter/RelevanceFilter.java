package com.aist.filter;


import com.aist.dto.CodeAnalyzeContextDTO;
import com.aist.tool.ToolResult;
import com.aist.util.DeepSeekUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Filters tool output for relevance using a separate LLM pass
 * to drop code snippets that are clearly unrelated to the question.
 */
@Slf4j
@Component
public class RelevanceFilter {

    /**
     * Pattern splitting code blocks in tool output.
     */
    private static final Pattern CODE_BLOCK_PATTERN = Pattern.compile(
            "###\\s*\\d+\\.\\s*([^\\n]+)\\n([\\s\\S]*?)(?=###\\s*\\d+\\.|$)"
    );

    /**
     * Minimum result length before filtering runs.
     */
    private static final int FILTER_THRESHOLD = 2000;

    /**
     * Filters a successful tool result, removing likely-irrelevant snippets.
     */
    public ToolResult filter(ToolResult result, CodeAnalyzeContextDTO context) {
        if (!result.isSuccess() || result.getResult() == null) {
            return result;
        }

        String content = result.getResult();
        if (content.length() < FILTER_THRESHOLD) {
            return result;
        }

        List<CodeBlock> blocks = parseCodeBlocks(content);
        if (blocks.size() <= 1) {
            return result;
        }

        log.info("Filtering tool output, {} code blocks", blocks.size());

        try {
            List<CodeBlock> relevantBlocks = filterBlocks(blocks, context);
            if (relevantBlocks.size() == blocks.size()) {
                return result;
            }

            String filteredContent = rebuildContent(relevantBlocks, blocks.size());
            log.info("Filtering done, kept {}/{} blocks", relevantBlocks.size(), blocks.size());
            return ToolResult.success(result.getToolName(), result.getArguments(), filteredContent);

        } catch (Exception e) {
            log.warn("Relevance filtering failed, returning original result: {}", e.getMessage());
            return result;
        }
    }

    private List<CodeBlock> parseCodeBlocks(String content) {
        List<CodeBlock> blocks = new ArrayList<>();
        Matcher matcher = CODE_BLOCK_PATTERN.matcher(content);
        while (matcher.find()) {
            blocks.add(new CodeBlock(matcher.group(1).trim(), matcher.group(0)));
        }
        return blocks;
    }

    private List<CodeBlock> filterBlocks(List<CodeBlock> blocks, CodeAnalyzeContextDTO context) throws Exception {
        DeepSeekUtil deepSeek = new DeepSeekUtil(context.getDeepseekApiKey());

        StringBuilder prompt = new StringBuilder();
        prompt.append("请判断以下代码片段与用户问题的相关性。\n\n");
        prompt.append("## 用户问题\n").append(context.getQuestion()).append("\n\n");
        if (context.getApiUrl() != null && !context.getApiUrl().isEmpty()) {
            prompt.append("## 相关接口\n").append(context.getApiUrl()).append("\n\n");
        }

        // Pass full code blocks for the LLM to judge
        prompt.append("## 代码片段\n\n");
        for (int i = 0; i < blocks.size(); i++) {
            prompt.append("### 片段 ").append(i + 1).append(": ").append(blocks.get(i).title).append("\n");
            prompt.append(blocks.get(i).content).append("\n\n");
        }

        prompt.append("---\n\n");
        prompt.append("请分析以上代码片段，返回**可能与用户问题相关**的片段编号（用逗号分隔）。\n");
        prompt.append("判断标准：\n");
        prompt.append("- 保留：与问题直接相关、可能被调用、提供上下文信息的代码\n");
        prompt.append("- 排除：明显无关的工具类、日志类、与业务完全无关的代码\n");
        prompt.append("- 不确定时保留\n\n");
        prompt.append("只返回编号，例如：1,2,4\n");
        prompt.append("如果全部可能相关，返回：ALL");

        String response = deepSeek.chat(prompt.toString());
        return parseFilterResponse(response, blocks);
    }

    private List<CodeBlock> parseFilterResponse(String response, List<CodeBlock> blocks) {
        response = response.trim().toUpperCase();
        if (response.contains("ALL") || response.contains("全部")) {
            return blocks;
        }

        List<CodeBlock> result = new ArrayList<>();
        Matcher matcher = Pattern.compile("\\d+").matcher(response);
        while (matcher.find()) {
            int index = Integer.parseInt(matcher.group()) - 1;
            if (index >= 0 && index < blocks.size()) {
                result.add(blocks.get(index));
            }
        }
        return result.isEmpty() ? blocks : result;
    }

    private String rebuildContent(List<CodeBlock> relevantBlocks, int totalCount) {
        StringBuilder sb = new StringBuilder();
        sb.append("（已筛选，保留 ").append(relevantBlocks.size())
                .append("/").append(totalCount).append(" 个相关代码块）\n\n");

        for (int i = 0; i < relevantBlocks.size(); i++) {
            CodeBlock block = relevantBlocks.get(i);
            sb.append("### ").append(i + 1).append(". ").append(block.title).append("\n");
            String blockContent = block.content;
            int titleEnd = blockContent.indexOf("\n");
            if (titleEnd > 0) {
                sb.append(blockContent.substring(titleEnd + 1));
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private static class CodeBlock {
        final String title;
        final String content;

        CodeBlock(String title, String content) {
            this.title = title;
            this.content = content;
        }
    }
}

