package com.aist.tool.impl;

import com.aist.dto.CodeAnalyzeContextDTO;
import com.aist.tool.ToolRequest;
import com.aist.tool.ToolResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 通用文件搜索工具
 * 支持全文搜索、正则表达式、行号定位
 */
@Slf4j
@Component
public class SearchFileTool extends AbstractTool {

    private static final int DEFAULT_CONTEXT_LINES = 3;
    private static final int MAX_MATCHES_PER_FILE = 10;
    private static final int MAX_FILES = 5;

    @Override
    public String getName() {
        return "SEARCH_FILE";
    }

    @Override
    public String getDescription() {
        return "在文件中搜索文本内容（支持关键词、正则、行号定位）";
    }

    @Override
    public String getParameterDescription() {
        return "搜索模式";
    }

    @Override
    public List<String> getExamples() {
        return List.of(
                "[TOOL_CALL:SEARCH_FILE:keyword:@PostMapping(\"/start\")]         # 搜索注解内容",
                "[TOOL_CALL:SEARCH_FILE:keyword:public class UserService]         # 搜索类定义",
                "[TOOL_CALL:SEARCH_FILE:regex:@PostMapping\\(\".*start.*\"\\)]    # 正则搜索",
                "[TOOL_CALL:SEARCH_FILE:line:UserService.java:359]                # 查看第359行",
                "[TOOL_CALL:SEARCH_FILE:line:UserService.java:100-120]            # 查看100-120行"
        );
    }

    @Override
    public String getUsageScenario() {
        return "搜索注解内容（如@PostMapping）、代码片段、查看指定行号附近的代码";
    }

    @Override
    public String getCapabilities() {
        return """
                支持：Java、XML、YML、Properties 等所有文本文件
                特点：全文搜索、正则表达式、行号定位、上下文显示（前后3行）

                搜索模式可选项：
                - keyword:关键词
                - regex:正则
                - line:文件名:行号
                - line:文件名:起始行-结束行
                """;
    }

    @Override
    public int getPriority() {
        return 8;
    }

    @Override
    protected String validateRequest(ToolRequest request) {
        if (!request.hasArguments()) {
            return "请指定搜索模式和参数\n" +
                    "格式: keyword:关键词 | regex:正则 | line:文件名:行号";
        }
        return null;
    }

    @Override
    protected ToolResult doExecute(ToolRequest request, CodeAnalyzeContextDTO context) {
        List<String> args = request.getArguments();
        log.info("文件搜索参数: {}", args);

        if (args.isEmpty()) {
            return ToolResult.error(getName(), "", "缺少搜索参数");
        }

        String mode = args.get(0).toLowerCase();

        switch (mode) {
            case "keyword" -> {
                // keyword模式：将后续所有参数用冒号连接作为关键词
                if (args.size() < 2) {
                    return ToolResult.error(getName(), mode, "keyword模式缺少搜索内容");
                }
                String keyword = String.join(":", args.subList(1, args.size()));
                log.info("关键词搜索: {}", keyword);
                return searchByKeyword(keyword, context);

            }
            case "regex" -> {
                // regex模式：将后续所有参数用冒号连接作为正则表达式
                if (args.size() < 2) {
                    return ToolResult.error(getName(), mode, "regex模式缺少正则表达式");
                }
                String regex = String.join(":", args.subList(1, args.size()));
                log.info("正则搜索: {}", regex);
                return searchByRegex(regex, context);
            }
            case "line" -> {
                // line模式：参数格式为 ["line", "文件名", "行号"] 或 ["line", "文件名", "起始行-结束行"]
                if (args.size() < 3) {
                    return ToolResult.error(getName(), mode,
                            "line模式参数不足\n正确格式: line:文件名:行号 或 line:文件名:起始行-结束行");
                }
                String fileName = args.get(1);
                String lineRange = args.get(2);
                log.info("行号定位: 文件={}, 行号={}", fileName, lineRange);
                return searchByLineNumber(fileName, lineRange, context);
            }
            default -> {
                // 兼容旧格式：如果第一个参数不是模式关键词，则将所有参数作为关键词搜索
                String keyword = String.join(":", args);
                log.info("默认关键词搜索: {}", keyword);
                return searchByKeyword(keyword, context);
            }
        }
    }

    /**
     * 按关键词搜索
     */
    private ToolResult searchByKeyword(String keyword, CodeAnalyzeContextDTO context) {
        log.info("关键词搜索: {}", keyword);

        try {
            Path projectPath = Paths.get(context.getProjectPath());
            String projectRoot = context.getProjectPath();
            List<FileMatch> matches = new ArrayList<>();

            // 遍历所有文本文件
            List<Path> files;
            try (Stream<Path> stream = Files.walk(projectPath)) {
                files = stream
                        .filter(Files::isRegularFile)
                        .filter(this::isTextFile)
                        .filter(p -> !isIgnored(p, projectRoot))
                        .toList();
            }

            for (Path file : files) {
                try {
                    String content = new String(Files.readAllBytes(file));
                    List<MatchedLine> matchedLines = findKeywordMatches(content, keyword);

                    if (!matchedLines.isEmpty()) {
                        matches.add(new FileMatch(file, matchedLines));
                        if (matches.size() >= MAX_FILES) {
                            break;
                        }
                    }
                } catch (IOException e) {
                    // 忽略无法读取的文件
                }
            }

            if (matches.isEmpty()) {
                return ToolResult.notFound(getName(), keyword,
                        "未找到包含 \"" + keyword + "\" 的文件\n" +
                                "提示: 请确认关键词拼写正确，或尝试使用 VIEW_METHOD 搜索代码符号");
            }

            return ToolResult.success(getName(), keyword, formatMatches(keyword, matches, context));

        } catch (IOException e) {
            return ToolResult.error(getName(), keyword, "搜索失败: " + e.getMessage());
        }
    }

    /**
     * 按正则表达式搜索
     */
    private ToolResult searchByRegex(String regex, CodeAnalyzeContextDTO context) {
        log.info("正则搜索: {}", regex);

        try {
            Pattern pattern = Pattern.compile(regex);
            Path projectPath = Paths.get(context.getProjectPath());
            String projectRoot = context.getProjectPath();
            List<FileMatch> matches = new ArrayList<>();

            List<Path> files;
            try (Stream<Path> stream = Files.walk(projectPath)) {
                files = stream
                        .filter(Files::isRegularFile)
                        .filter(this::isTextFile)
                        .filter(p -> !isIgnored(p, projectRoot))
                        .toList();
            }

            for (Path file : files) {
                try {
                    String content = new String(Files.readAllBytes(file));
                    List<MatchedLine> matchedLines = findRegexMatches(content, pattern);

                    if (!matchedLines.isEmpty()) {
                        matches.add(new FileMatch(file, matchedLines));
                        if (matches.size() >= MAX_FILES) {
                            break;
                        }
                    }
                } catch (IOException e) {
                    // 忽略无法读取的文件
                }
            }

            if (matches.isEmpty()) {
                return ToolResult.notFound(getName(), regex,
                        "未找到匹配正则表达式 \"" + regex + "\" 的内容");
            }

            return ToolResult.success(getName(), regex, formatMatches(regex, matches, context));

        } catch (Exception e) {
            return ToolResult.error(getName(), regex, "正则搜索失败: " + e.getMessage());
        }
    }

    /**
     * 按行号搜索
     */
    private ToolResult searchByLineNumber(String fileName, String lineRange, CodeAnalyzeContextDTO context) {
        log.info("行号定位: 文件={}, 行号={}", fileName, lineRange);

        try {
            fileName = fileName.trim();
            lineRange = lineRange.trim();

            String lineSpec = fileName + ":" + lineRange; // 用于日志和错误消息

            // 查找文件
            Optional<Path> fileOpt = searchFile(context.getProjectPath(), fileName);
            if (fileOpt.isEmpty()) {
                return ToolResult.notFound(getName(), lineSpec,
                        "未找到文件: " + fileName + "\n提示: 请确认文件名正确");
            }

            Path file = fileOpt.get();
            String content = new String(Files.readAllBytes(file));
            String[] lines = content.split("\n");

            int startLine, endLine;
            if (lineRange.contains("-")) {
                // 范围模式: 100-120
                String[] rangeParts = lineRange.split("-");
                startLine = Integer.parseInt(rangeParts[0].trim());
                endLine = Integer.parseInt(rangeParts[1].trim());
            } else {
                // 单行模式: 359 (显示前后3行)
                int targetLine = Integer.parseInt(lineRange);
                startLine = Math.max(1, targetLine - DEFAULT_CONTEXT_LINES);
                endLine = Math.min(lines.length, targetLine + DEFAULT_CONTEXT_LINES);
            }

            // 验证行号范围
            if (startLine < 1 || endLine > lines.length || startLine > endLine) {
                return ToolResult.error(getName(), lineSpec,
                        "行号超出范围，文件共 " + lines.length + " 行");
            }

            // 构建结果
            StringBuilder result = new StringBuilder();
            result.append("## 文件内容查看\n\n");
            result.append("**文件**: `").append(getRelativePath(context.getProjectPath(), file)).append("`\n");
            result.append("**行号范围**: ").append(startLine).append("-").append(endLine).append("\n\n");
            result.append("```").append(getCodeLanguage(file)).append("\n");

            for (int i = startLine - 1; i < endLine; i++) {
                result.append(String.format("%4d | %s\n", i + 1, lines[i]));
            }

            result.append("```\n");

            return ToolResult.success(getName(), lineSpec, result.toString());

        } catch (NumberFormatException e) {
            String lineSpec = fileName + ":" + lineRange;
            return ToolResult.error(getName(), lineSpec, "行号格式错误: " + e.getMessage());
        } catch (IOException e) {
            String lineSpec = fileName + ":" + lineRange;
            return ToolResult.error(getName(), lineSpec, "读取文件失败: " + e.getMessage());
        }
    }

    /**
     * 查找关键词匹配
     */
    private List<MatchedLine> findKeywordMatches(String content, String keyword) {
        List<MatchedLine> matches = new ArrayList<>();
        String[] lines = content.split("\n");
        String lowerKeyword = keyword.toLowerCase();

        for (int i = 0; i < lines.length; i++) {
            if (lines[i].toLowerCase().contains(lowerKeyword)) {
                int startContext = Math.max(0, i - DEFAULT_CONTEXT_LINES);
                int endContext = Math.min(lines.length, i + DEFAULT_CONTEXT_LINES + 1);

                List<String> contextLines = new ArrayList<>(Arrays.asList(lines).subList(startContext, endContext));

                matches.add(new MatchedLine(i + 1, lines[i], contextLines, startContext + 1));

                if (matches.size() >= MAX_MATCHES_PER_FILE) {
                    break;
                }
            }
        }

        return matches;
    }

    /**
     * 查找正则匹配
     */
    private List<MatchedLine> findRegexMatches(String content, Pattern pattern) {
        List<MatchedLine> matches = new ArrayList<>();
        String[] lines = content.split("\n");

        for (int i = 0; i < lines.length; i++) {
            Matcher matcher = pattern.matcher(lines[i]);
            if (matcher.find()) {
                int startContext = Math.max(0, i - DEFAULT_CONTEXT_LINES);
                int endContext = Math.min(lines.length, i + DEFAULT_CONTEXT_LINES + 1);

                List<String> contextLines = new ArrayList<>(Arrays.asList(lines).subList(startContext, endContext));

                matches.add(new MatchedLine(i + 1, lines[i], contextLines, startContext + 1));

                if (matches.size() >= MAX_MATCHES_PER_FILE) {
                    break;
                }
            }
        }

        return matches;
    }

    /**
     * 格式化匹配结果
     */
    private String formatMatches(String query, List<FileMatch> matches, CodeAnalyzeContextDTO context) {
        StringBuilder result = new StringBuilder();
        result.append("## 文件搜索结果\n\n");
        result.append("**搜索内容**: `").append(query).append("`\n");
        result.append("**匹配文件数**: ").append(matches.size()).append("\n\n");

        for (int i = 0; i < matches.size(); i++) {
            FileMatch match = matches.get(i);
            result.append("### ").append(i + 1).append(". ")
                    .append(match.file.getFileName()).append("\n\n");
            result.append("**路径**: `").append(getRelativePath(context.getProjectPath(), match.file))
                    .append("`\n");
            result.append("**匹配数**: ").append(match.matchedLines.size()).append(" 处\n\n");

            for (MatchedLine matchedLine : match.matchedLines) {
                result.append("**行 ").append(matchedLine.lineNumber).append("**:\n");
                result.append("```").append(getCodeLanguage(match.file)).append("\n");

                for (int j = 0; j < matchedLine.contextLines.size(); j++) {
                    int lineNum = matchedLine.contextStartLine + j;
                    String prefix = (lineNum == matchedLine.lineNumber) ? "→ " : "  ";
                    result.append(String.format("%s%4d | %s\n", prefix, lineNum, matchedLine.contextLines.get(j)));
                }

                result.append("```\n\n");
            }

            result.append("---\n\n");
        }

        if (matches.size() >= MAX_FILES) {
            result.append("*注: 结果已限制为前 ").append(MAX_FILES).append(" 个文件*\n");
        }

        return result.toString();
    }

    /**
     * 根据文件扩展名返回对应的代码语言（用于 Markdown 代码块语法高亮）
     */
    private String getCodeLanguage(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        if (name.endsWith(".java")) return "java";
        if (name.endsWith(".xml")) return "xml";
        if (name.endsWith(".yml") || name.endsWith(".yaml")) return "yaml";
        if (name.endsWith(".properties")) return "properties";
        if (name.endsWith(".sql")) return "sql";
        if (name.endsWith(".ts")) return "typescript";
        if (name.endsWith(".js")) return "javascript";
        if (name.endsWith(".vue")) return "vue";
        if (name.endsWith(".html")) return "html";
        if (name.endsWith(".css")) return "css";
        return "text";
    }

    /**
     * 判断是否为文本文件
     */
    private boolean isTextFile(Path path) {
        String fileName = path.getFileName().toString().toLowerCase();
        return fileName.endsWith(".java") ||
                fileName.endsWith(".xml") ||
                fileName.endsWith(".yml") ||
                fileName.endsWith(".yaml") ||
                fileName.endsWith(".properties") ||
                fileName.endsWith(".txt") ||
                fileName.endsWith(".md") ||
                fileName.endsWith(".sql") ||
                fileName.endsWith(".js") ||
                fileName.endsWith(".ts") ||
                fileName.endsWith(".vue") ||
                fileName.endsWith(".html") ||
                fileName.endsWith(".css");
    }

    /**
     * 文件匹配结果
     */
    private static class FileMatch {
        Path file;
        List<MatchedLine> matchedLines;

        FileMatch(Path file, List<MatchedLine> matchedLines) {
            this.file = file;
            this.matchedLines = matchedLines;
        }
    }

    /**
     * 匹配行信息
     */
    private static class MatchedLine {
        int lineNumber;
        String content;
        List<String> contextLines;
        int contextStartLine;

        MatchedLine(int lineNumber, String content, List<String> contextLines, int contextStartLine) {
            this.lineNumber = lineNumber;
            this.content = content;
            this.contextLines = contextLines;
            this.contextStartLine = contextStartLine;
        }
    }
}
