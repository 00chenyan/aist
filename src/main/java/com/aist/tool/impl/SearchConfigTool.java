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
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Search configuration files (properties, yaml, key config filenames).
 */
@Slf4j
@Component
public class SearchConfigTool extends AbstractTool {

    private static final Set<String> CONFIG_EXTENSIONS = Set.of(".yml", ".yaml", ".properties", ".xml");
    private static final Set<String> CONFIG_FILE_NAMES = Set.of(
            "application", "bootstrap", "config", "settings", "pom", "build.gradle", ".env"
    );

    @Override
    public String getName() {
        return "SEARCH_CONFIG";
    }

    @Override
    public String getDescription() {
        return "搜索配置文件（yml、yaml、properties、xml等）";
    }

    @Override
    public String getParameterDescription() {
        return "配置项关键词";
    }

    @Override
    public List<String> getExamples() {
        return List.of(
                "[TOOL_CALL:SEARCH_CONFIG:datasource]         # datasource settings",
                "[TOOL_CALL:SEARCH_CONFIG:redis]              # Redis settings",
                "[TOOL_CALL:SEARCH_CONFIG:server.port]        # port settings"
        );
    }

    @Override
    public String getUsageScenario() {
        return "搜索 YML/YAML/Properties 配置文件";
    }

    @Override
    public String getCapabilities() {
        return """
                支持：YML、YAML、Properties、XML 配置文件
                特点：全文搜索，支持配置项名称和值
                不支持：代码文件（请使用 SEARCH_FILE）
                """;
    }

    @Override
    public int getPriority() {
        return 10; // relatively high priority
    }

    @Override
    protected String validateRequest(ToolRequest request) {
        if (!request.hasArguments()) {
            return "请指定配置项关键词";
        }
        return null;
    }

    @Override
    protected ToolResult doExecute(ToolRequest request, CodeAnalyzeContextDTO context) {
        String keyword = request.getFirstArgument();
        log.info("search config files: {}", keyword);

        StringBuilder result = new StringBuilder();
        String searchKeyword = keyword.toLowerCase().trim();

        try {
            Path projectPath = Paths.get(context.getProjectPath());
            String projectRoot = context.getProjectPath();

            List<Path> configFiles;
            try (Stream<Path> stream = Files.walk(projectPath)) {
                configFiles = stream
                        .filter(Files::isRegularFile)
                        .filter(p -> !isIgnored(p, projectRoot))
                        .filter(p -> {
                            String fileName = p.getFileName().toString().toLowerCase();
                            String filePath = p.toString().toLowerCase();

                            boolean isConfigExt = CONFIG_EXTENSIONS.stream().anyMatch(fileName::endsWith);
                            boolean isConfigName = CONFIG_FILE_NAMES.stream().anyMatch(fileName::contains);
                            boolean inResources = filePath.contains("resources");

                            return (isConfigExt && (isConfigName || inResources)) ||
                                    fileName.endsWith(".properties") ||
                                    fileName.equals(".env") ||
                                    fileName.equals("pom.xml");
                        })
                        .collect(Collectors.toList());
            }

            log.info("found {} config file(s)", configFiles.size());

            List<ConfigMatch> matches = new ArrayList<>();

            for (Path file : configFiles) {
                try {
                    String content = new String(Files.readAllBytes(file));
                    if (content.toLowerCase().contains(searchKeyword)) {
                        List<String> matchedLines = extractConfigMatches(content, searchKeyword);
                        if (!matchedLines.isEmpty()) {
                            int score = calculateScore(file.toString(), matchedLines.size(), searchKeyword);
                            matches.add(new ConfigMatch(file, matchedLines, score));
                        }
                    }
                } catch (IOException e) {
                    // skip
                }
            }

            matches.sort((a, b) -> b.score - a.score);

            if (matches.isEmpty()) {
                return ToolResult.notFound(getName(), keyword,
                        "未找到包含 \"" + keyword + "\" 的配置\n" +
                                "提示: 可以尝试搜索 datasource、redis、server.port 等");
            }

            result.append("找到 ").append(matches.size()).append(" 个配置文件包含相关配置:\n\n");

            int showCount = Math.min(10, matches.size());
            for (int i = 0; i < showCount; i++) {
                ConfigMatch match = matches.get(i);
                String fileType = getConfigFileType(match.file.toString());

                result.append("### ").append(i + 1).append(". ")
                        .append(match.file.getFileName()).append("\n");
                result.append("**路径**: `").append(getRelativePath(context.getProjectPath(), match.file)).append("`\n\n");
                result.append("**匹配的配置项**:\n```").append(fileType).append("\n");

                for (String line : match.matchedLines) {
                    result.append(line).append("\n");
                }
                result.append("```\n\n");
            }

            if (matches.size() > showCount) {
                result.append("*还有 ").append(matches.size() - showCount).append(" 个文件包含相关配置*\n");
            }

        } catch (IOException e) {
            return ToolResult.error(getName(), keyword, "搜索配置文件失败: " + e.getMessage());
        }

        return ToolResult.success(getName(), keyword, result.toString());
    }

    private List<String> extractConfigMatches(String content, String keyword) {
        List<String> result = new ArrayList<>();
        String[] lines = content.split("\n");
        int lastAddedLine = -1; // last emitted line index (0-based) to limit overlap

        for (int i = 0; i < lines.length; i++) {
            if (lines[i].toLowerCase().contains(keyword)) {
                int start = Math.max(0, i - 2);
                int end = Math.min(lines.length, i + 3);

                // separator only when there is a gap from the previous block
                if (!result.isEmpty() && start > lastAddedLine + 1) {
                    result.add("...");
                }

                // skip already-printed lines to avoid duplicate context
                int outputStart = Math.max(start, lastAddedLine + 1);
                for (int j = outputStart; j < end; j++) {
                    result.add((j == i ? "→ " : "  ") + lines[j]);
                }
                lastAddedLine = end - 1;

                if (result.size() > 30) {
                    result.add("... (更多配置项已省略)");
                    break;
                }
            }
        }
        return result;
    }

    private int calculateScore(String filePath, int matchCount, String keyword) {
        int score = matchCount * 10;
        if (filePath.contains("application")) score += 30;
        if (filePath.contains("resources")) score += 20;
        if (filePath.toLowerCase().contains(keyword)) score += 25;
        return score;
    }

    private String getConfigFileType(String filePath) {
        if (filePath.endsWith(".yml") || filePath.endsWith(".yaml")) return "yaml";
        if (filePath.endsWith(".properties")) return "properties";
        if (filePath.endsWith(".xml")) return "xml";
        return "text";
    }

    private static class ConfigMatch {
        Path file;
        List<String> matchedLines;
        int score;

        ConfigMatch(Path file, List<String> matchedLines, int score) {
            this.file = file;
            this.matchedLines = matchedLines;
            this.score = score;
        }
    }
}

