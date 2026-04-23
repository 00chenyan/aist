package com.aist.tool.impl;

import com.aist.dto.CodeAnalyzeContextDTO;
import com.aist.tool.Tool;
import com.aist.tool.ToolRequest;
import com.aist.tool.ToolResult;
import com.aist.util.GitIgnoreParser;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

/**
 * 工具抽象基类
 * 提供通用的工具方法
 *
 */
@Slf4j
public abstract class AbstractTool implements Tool {

    // 缓存 GitIgnoreParser 实例
    private GitIgnoreParser gitIgnoreParser;

    @Override
    public ToolResult execute(ToolRequest request, CodeAnalyzeContextDTO context) {
        log.info("执行工具 {}: {}", getName(), request.getArgumentsString());

        try {
            // 参数校验
            String validationError = validateRequest(request);
            if (validationError != null) {
                return ToolResult.error(getName(), request.getArgumentsString(), validationError);
            }

            // 执行具体逻辑
            return doExecute(request, context);
        } catch (Exception e) {
            log.error("工具 {} 执行失败: {}", getName(), e.getMessage(), e);
            return ToolResult.error(getName(), request.getArgumentsString(), e.getMessage());
        }
    }

    /**
     * 执行具体逻辑（子类实现）
     */
    protected abstract ToolResult doExecute(ToolRequest request, CodeAnalyzeContextDTO context);

    /**
     * 参数校验（子类可覆盖）
     *
     * @return 错误信息，null表示校验通过
     */
    protected String validateRequest(ToolRequest request) {
        return null;
    }

    /**
     * 在项目中搜索文件
     */
    protected Optional<Path> searchFile(String projectRoot, String fileName) {
        try {
            Path projectPath = Paths.get(projectRoot);
            GitIgnoreParser parser = getOrCreateGitIgnoreParser(projectRoot);

            try (var stream = Files.walk(projectPath)) {
                return stream
                        .filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().equalsIgnoreCase(fileName))
                        .filter(p -> !parser.isIgnored(p))
                        .findFirst();
            }
        } catch (IOException e) {
            log.error("搜索文件失败: {}", e.getMessage());
            return Optional.empty();
        }
    }


    /**
     * 获取相对路径
     */
    protected String getRelativePath(String projectRoot, Path path) {
        try {
            return Paths.get(projectRoot).relativize(path).toString();
        } catch (Exception e) {
            return path.toString();
        }
    }

    /**
     * 获取或创建 GitIgnoreParser 实例
     */
    protected GitIgnoreParser getOrCreateGitIgnoreParser(String projectRoot) {
        if (gitIgnoreParser == null) {
            gitIgnoreParser = new GitIgnoreParser(projectRoot);
        }
        return gitIgnoreParser;
    }

    /**
     * 判断路径是否应该被忽略（基于 .gitignore）
     */
    protected boolean isIgnored(Path path, String projectRoot) {
        return getOrCreateGitIgnoreParser(projectRoot).isIgnored(path);
    }
}

