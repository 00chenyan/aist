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
 * Abstract base for tools.
 * Provides shared helpers.
 */
@Slf4j
public abstract class AbstractTool implements Tool {

    private GitIgnoreParser gitIgnoreParser;

    @Override
    public ToolResult execute(ToolRequest request, CodeAnalyzeContextDTO context) {
        log.info("Executing tool {}: {}", getName(), request.getArgumentsString());

        try {
            String validationError = validateRequest(request);
            if (validationError != null) {
                return ToolResult.error(getName(), request.getArgumentsString(), validationError);
            }

            return doExecute(request, context);
        } catch (Exception e) {
            log.error("Tool {} failed: {}", getName(), e.getMessage(), e);
            return ToolResult.error(getName(), request.getArgumentsString(), e.getMessage());
        }
    }

    /**
     * Subclass implements actual behavior.
     */
    protected abstract ToolResult doExecute(ToolRequest request, CodeAnalyzeContextDTO context);

    /**
     * Optional request validation; override when needed.
     *
     * @return error message, or null if valid
     */
    protected String validateRequest(ToolRequest request) {
        return null;
    }

    /**
     * Finds first matching file under project root (respects .gitignore).
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
            log.error("File search failed: {}", e.getMessage());
            return Optional.empty();
        }
    }


    /**
     * Path relative to project root.
     */
    protected String getRelativePath(String projectRoot, Path path) {
        try {
            return Paths.get(projectRoot).relativize(path).toString();
        } catch (Exception e) {
            return path.toString();
        }
    }

    /**
     * Cached {@link GitIgnoreParser} for the project root.
     */
    protected GitIgnoreParser getOrCreateGitIgnoreParser(String projectRoot) {
        if (gitIgnoreParser == null) {
            gitIgnoreParser = new GitIgnoreParser(projectRoot);
        }
        return gitIgnoreParser;
    }

    /**
     * Whether path is ignored per {@code .gitignore}.
     */
    protected boolean isIgnored(Path path, String projectRoot) {
        return getOrCreateGitIgnoreParser(projectRoot).isIgnored(path);
    }
}
