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
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 搜索Mapper XML工具
 */
@Slf4j
@Component
public class SearchMapperXmlTool extends AbstractTool {

    @Override
    public String getName() {
        return "SEARCH_MAPPER_XML";
    }

    @Override
    public String getDescription() {
        return "搜索MyBatis Mapper XML中的SQL";
    }

    @Override
    public String getParameterDescription() {
        return "Mapper名或方法名";
    }

    @Override
    public List<String> getExamples() {
        return List.of(
                "[TOOL_CALL:SEARCH_MAPPER_XML:UserMapper]     # 搜索 Mapper 文件",
                "[TOOL_CALL:SEARCH_MAPPER_XML:selectById]     # 搜索 SQL 语句",
                "[TOOL_CALL:SEARCH_MAPPER_XML:insert]         # 搜索插入语句"
        );
    }

    @Override
    public String getUsageScenario() {
        return "搜索 MyBatis Mapper XML 文件中的 SQL 语句（Reflex 不支持）";
    }

    @Override
    public String getCapabilities() {
        return """
                支持：MyBatis Mapper XML 文件
                特点：提取 SQL 语句、Mapper 方法、命名空间
                不支持：Java 代码（请使用 VIEW_METHOD）
                """;
    }

    @Override
    public int getPriority() {
        return 16; // MyBatis XML 搜索优先级中等
    }

    @Override
    protected String validateRequest(ToolRequest request) {
        if (!request.hasArguments()) {
            return "请指定Mapper名或方法名";
        }
        return null;
    }

    @Override
    protected ToolResult doExecute(ToolRequest request, CodeAnalyzeContextDTO context) {
        String mapperNameOrMethod = request.getFirstArgument();
        log.info("搜索Mapper XML: {}", mapperNameOrMethod);

        StringBuilder result = new StringBuilder();
        String keyword = mapperNameOrMethod.toLowerCase().trim();

        try {
            Path projectPath = Paths.get(context.getProjectPath());
            String projectRoot = context.getProjectPath();

            List<Path> xmlFiles;
            try (Stream<Path> stream = Files.walk(projectPath)) {
                xmlFiles = stream
                        .filter(Files::isRegularFile)
                        .filter(p -> !isIgnored(p, projectRoot))
                        .filter(p -> p.toString().endsWith(".xml"))
                        .toList();
            }

            int foundCount = 0;

            for (Path xmlFile : xmlFiles) {
                String content = new String(Files.readAllBytes(xmlFile));

                if (!content.contains("<!DOCTYPE mapper") && !content.contains("<mapper")) {
                    continue;
                }

                // 提取实际 namespace 值，检查其是否包含关键词
                Pattern checkNsPattern = Pattern.compile("namespace\\s*=\\s*\"([^\"]+)\"");
                Matcher checkNsMatcher = checkNsPattern.matcher(content);
                boolean matchNamespace = checkNsMatcher.find() &&
                        checkNsMatcher.group(1).toLowerCase().contains(keyword);

                // 用正则提取所有 id 属性值，判断是否有方法名包含关键词（精确子串匹配）
                Pattern idCheckPattern = Pattern.compile("id\\s*=\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
                Matcher idCheckMatcher = idCheckPattern.matcher(content);
                boolean matchMethodId = false;
                while (idCheckMatcher.find()) {
                    if (idCheckMatcher.group(1).toLowerCase().contains(keyword)) {
                        matchMethodId = true;
                        break;
                    }
                }

                if (matchNamespace || matchMethodId) {
                    result.append("\n### 文件: ").append(xmlFile.getFileName()).append("\n");
                    result.append("路径: ").append(getRelativePath(context.getProjectPath(), xmlFile)).append("\n\n");

                    // 提取namespace
                    Pattern nsPattern = Pattern.compile("namespace\\s*=\\s*\"([^\"]+)\"");
                    Matcher nsMatcher = nsPattern.matcher(content);
                    if (nsMatcher.find()) {
                        result.append("Namespace: `").append(nsMatcher.group(1)).append("`\n\n");
                    }

                    result.append("**SQL 语句:**\n\n");

                    // 提取SQL块
                    Pattern sqlPattern = Pattern.compile(
                            "<(select|insert|update|delete)\\s+[^>]*id\\s*=\\s*\"([^\"]+)\"[^>]*>([\\s\\S]*?)</\\1>",
                            Pattern.CASE_INSENSITIVE
                    );
                    Matcher sqlMatcher = sqlPattern.matcher(content);

                    int sqlCount = 0;
                    while (sqlMatcher.find()) {
                        String sqlType = sqlMatcher.group(1).toUpperCase();
                        String methodId = sqlMatcher.group(2);
                        String sqlBody = sqlMatcher.group(3).trim();

                        // namespace 匹配时展示该文件所有 SQL；否则按方法 ID 过滤
                        if (!matchNamespace && !methodId.toLowerCase().contains(keyword)) {
                            continue;
                        }

                        result.append("#### ").append(sqlType).append(": `").append(methodId).append("`\n");
                        result.append("```xml\n");
                        result.append(cleanSqlBody(sqlBody));
                        result.append("\n```\n\n");
                        sqlCount++;

                        if (sqlCount >= 10) {
                            result.append("... (更多 SQL 已省略)\n\n");
                            break;
                        }
                    }

                    foundCount++;
                    if (foundCount >= 3) {
                        result.append("\n*注: 结果已限制为前3个匹配的文件*\n");
                        break;
                    }
                }
            }

            if (foundCount == 0) {
                return ToolResult.notFound(getName(), mapperNameOrMethod,
                        "未找到匹配的 Mapper XML: " + mapperNameOrMethod +
                                "\n提示: 可以搜索 Mapper 类名（如 UserMapper）或具体方法名（如 selectById）");
            }

        } catch (IOException e) {
            return ToolResult.error(getName(), mapperNameOrMethod, "搜索 Mapper XML 失败: " + e.getMessage());
        }

        return ToolResult.success(getName(), mapperNameOrMethod, result.toString());
    }

    /**
     * 清理SQL内容
     */
    private String cleanSqlBody(String sql) {
        if (sql == null) return "";
        String[] lines = sql.split("\n");
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                sb.append("  ").append(trimmed).append("\n");
            }
        }
        return sb.toString().trim();
    }
}

