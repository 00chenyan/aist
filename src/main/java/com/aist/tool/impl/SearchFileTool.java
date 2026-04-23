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
 * General-purpose file search tool.
 * Supports full-text search, regular expressions, and line-number navigation.
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
                "[TOOL_CALL:SEARCH_FILE:keyword:@PostMapping(\"/start\")]         # search annotation",
                "[TOOL_CALL:SEARCH_FILE:keyword:public class UserService]         # search class definition",
                "[TOOL_CALL:SEARCH_FILE:regex:@PostMapping\\(\".*start.*\"\\)]    # regex search",
                "[TOOL_CALL:SEARCH_FILE:line:UserService.java:359]                # view line 359",
                "[TOOL_CALL:SEARCH_FILE:line:UserService.java:100-120]            # view lines 100–120"
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
        log.info("file search args: {}", args);

        if (args.isEmpty()) {
            return ToolResult.error(getName(), "", "缺少搜索参数");
        }

        String mode = args.get(0).toLowerCase();

        switch (mode) {
            case "keyword" -> {
                // keyword mode: join remaining args with colons as the search term
                if (args.size() < 2) {
                    return ToolResult.error(getName(), mode, "keyword模式缺少搜索内容");
                }
                String keyword = String.join(":", args.subList(1, args.size()));
                log.info("keyword search: {}", keyword);
                return searchByKeyword(keyword, context);

            }
            case "regex" -> {
                // regex mode: join remaining args with colons as the pattern
                if (args.size() < 2) {
                    return ToolResult.error(getName(), mode, "regex模式缺少正则表达式");
                }
                String regex = String.join(":", args.subList(1, args.size()));
                log.info("regex search: {}", regex);
                return searchByRegex(regex, context);
            }
            case "line" -> {
                // line mode: ["line", "fileName", "lineOrRange"] e.g. start-end
                if (args.size() < 3) {
                    return ToolResult.error(getName(), mode,
                            "line模式参数不足\n正确格式: line:文件名:行号 或 line:文件名:起始行-结束行");
                }
                String fileName = args.get(1);
                String lineRange = args.get(2);
                log.info("line navigation: file={}, line={}", fileName, lineRange);
                return searchByLineNumber(fileName, lineRange, context);
            }
            default -> {
                // legacy: first arg is not a mode keyword; treat all args as keyword search
                String keyword = String.join(":", args);
                log.info("default keyword search: {}", keyword);
                return searchByKeyword(keyword, context);
            }
        }
    }

    /**
     * Search by keyword.
     */
    private ToolResult searchByKeyword(String keyword, CodeAnalyzeContextDTO context) {
        log.info("keyword search: {}", keyword);

        try {
            Path projectPath = Paths.get(context.getProjectPath());
            String projectRoot = context.getProjectPath();
            List<FileMatch> matches = new ArrayList<>();

            // walk all text files
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
                    // skip unreadable files
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
     * Search by regular expression.
     */
    private ToolResult searchByRegex(String regex, CodeAnalyzeContextDTO context) {
        log.info("regex search: {}", regex);

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
                    // skip unreadable files
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
     * Search by line number or line range.
     */
    private ToolResult searchByLineNumber(String fileName, String lineRange, CodeAnalyzeContextDTO context) {
        log.info("line navigation: file={}, line={}", fileName, lineRange);

        try {
            fileName = fileName.trim();
            lineRange = lineRange.trim();

            String lineSpec = fileName + ":" + lineRange; // for logs and error messages

            // locate file
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
                // range mode: 100-120
                String[] rangeParts = lineRange.split("-");
                startLine = Integer.parseInt(rangeParts[0].trim());
                endLine = Integer.parseInt(rangeParts[1].trim());
            } else {
                // single line: 359 (3 lines of context on each side)
                int targetLine = Integer.parseInt(lineRange);
                startLine = Math.max(1, targetLine - DEFAULT_CONTEXT_LINES);
                endLine = Math.min(lines.length, targetLine + DEFAULT_CONTEXT_LINES);
            }

            // validate line range
            if (startLine < 1 || endLine > lines.length || startLine > endLine) {
                return ToolResult.error(getName(), lineSpec,
                        "行号超出范围，文件共 " + lines.length + " 行");
            }

            // build result
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
     * Find keyword matches.
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
     * Find regex matches.
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
     * Format search results.
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
     * Return Markdown code-fence language from file extension.
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
     * Whether the path is treated as a text file for search.
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
     * Per-file match aggregate.
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
     * One matched line with context.
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
