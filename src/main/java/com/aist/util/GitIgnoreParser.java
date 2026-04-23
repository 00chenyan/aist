package com.aist.util;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * .gitignore 文件解析器
 * 用于解析 .gitignore 文件并判断路径是否应该被忽略
 */
@Slf4j
public class GitIgnoreParser {

    private final Path projectRoot;
    private final List<PathMatcher> ignorePatterns = new ArrayList<>();
    private final List<PathMatcher> negatePatterns = new ArrayList<>();

    /**
     * 构造函数
     *
     * @param projectRoot 项目根目录
     */
    public GitIgnoreParser(String projectRoot) {
        this.projectRoot = Paths.get(projectRoot);
        loadGitIgnore();
    }

    /**
     * 加载 .gitignore 文件
     */
    private void loadGitIgnore() {
        Path gitignorePath = projectRoot.resolve(".gitignore");

        if (!Files.exists(gitignorePath)) {
            log.debug("未找到 .gitignore 文件: {}", gitignorePath);
            addDefaultPatterns();
            return;
        }

        try {
            List<String> lines = Files.readAllLines(gitignorePath);
            for (String line : lines) {
                parseLine(line.trim());
            }
            log.debug("成功加载 .gitignore，共 {} 个忽略规则，{} 个否定规则",
                    ignorePatterns.size(), negatePatterns.size());
        } catch (IOException e) {
            log.warn("读取 .gitignore 文件失败: {}", e.getMessage());
            addDefaultPatterns();
        }
    }

    /**
     * 解析单行规则
     */
    private void parseLine(String line) {
        // 跳过空行和注释
        if (line.isEmpty() || line.startsWith("#")) {
            return;
        }

        boolean isNegate = line.startsWith("!");
        if (isNegate) {
            line = line.substring(1);
        }

        // 转换 gitignore 模式为 glob 模式
        String globPattern = convertToGlob(line);

        try {
            PathMatcher matcher = projectRoot.getFileSystem().getPathMatcher("glob:" + globPattern);
            if (isNegate) {
                negatePatterns.add(matcher);
            } else {
                ignorePatterns.add(matcher);
            }
        } catch (Exception e) {
            log.debug("无法解析 gitignore 规则: {}", line);
        }
    }

    /**
     * 将 gitignore 模式转换为 glob 模式
     */
    private String convertToGlob(String pattern) {
        // 移除前导斜杠
        if (pattern.startsWith("/")) {
            pattern = pattern.substring(1);
        }

        // 如果以斜杠结尾，表示目录
        boolean isDirectory = pattern.endsWith("/");
        if (isDirectory) {
            pattern = pattern.substring(0, pattern.length() - 1);
        }

        // 转换为 glob 模式
        StringBuilder glob = new StringBuilder("**");

        // 如果不是以 ** 开头，添加路径分隔符
        if (!pattern.startsWith("**")) {
            glob.append("/");
        }

        glob.append(pattern);

        // 如果是目录模式，匹配目录及其所有内容
        if (isDirectory) {
            glob.append("/**");
        }

        return glob.toString();
    }

    /**
     * 添加默认忽略模式（当没有 .gitignore 文件时）
     */
    private void addDefaultPatterns() {
        String[] defaultPatterns = {
                "**/target/**",
                "**/build/**",
                "**/.git/**",
                "**/node_modules/**",
                "**/dist/**",
                "**/.idea/**",
                "**/.vscode/**",
                "**/.settings/**",
                "**/*.class",
                "**/*.log"
        };

        for (String pattern : defaultPatterns) {
            try {
                PathMatcher matcher = projectRoot.getFileSystem().getPathMatcher("glob:" + pattern);
                ignorePatterns.add(matcher);
            } catch (Exception e) {
                log.debug("无法添加默认规则: {}", pattern);
            }
        }
    }

    /**
     * 判断路径是否应该被忽略
     *
     * @param path 要检查的路径
     * @return true 如果应该被忽略
     */
    public boolean isIgnored(Path path) {
        // 获取相对路径
        Path relativePath;
        try {
            relativePath = projectRoot.relativize(path);
        } catch (Exception e) {
            // 如果无法获取相对路径，使用绝对路径
            relativePath = path;
        }

        // 检查否定规则（! 开头的规则）
        for (PathMatcher matcher : negatePatterns) {
            if (matcher.matches(relativePath)) {
                return false; // 明确不忽略
            }
        }

        // 检查忽略规则
        for (PathMatcher matcher : ignorePatterns) {
            if (matcher.matches(relativePath)) {
                return true; // 应该忽略
            }
        }

        return false; // 不忽略
    }
}

