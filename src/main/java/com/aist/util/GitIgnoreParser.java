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
 * Parser for {@code .gitignore} files.
 * Parses rules and determines whether a path should be ignored.
 */
@Slf4j
public class GitIgnoreParser {

    private final Path projectRoot;
    private final List<PathMatcher> ignorePatterns = new ArrayList<>();
    private final List<PathMatcher> negatePatterns = new ArrayList<>();

    /**
     * Constructor.
     *
     * @param projectRoot project root directory
     */
    public GitIgnoreParser(String projectRoot) {
        this.projectRoot = Paths.get(projectRoot);
        loadGitIgnore();
    }

    /**
     * Loads the {@code .gitignore} file.
     */
    private void loadGitIgnore() {
        Path gitignorePath = projectRoot.resolve(".gitignore");

        if (!Files.exists(gitignorePath)) {
            log.debug(".gitignore not found: {}", gitignorePath);
            addDefaultPatterns();
            return;
        }

        try {
            List<String> lines = Files.readAllLines(gitignorePath);
            for (String line : lines) {
                parseLine(line.trim());
            }
            log.debug("Loaded .gitignore: {} ignore rules, {} negate rules",
                    ignorePatterns.size(), negatePatterns.size());
        } catch (IOException e) {
            log.warn("Failed to read .gitignore: {}", e.getMessage());
            addDefaultPatterns();
        }
    }

    /**
     * Parses a single rule line.
     */
    private void parseLine(String line) {
        // Skip blank lines and comments
        if (line.isEmpty() || line.startsWith("#")) {
            return;
        }

        boolean isNegate = line.startsWith("!");
        if (isNegate) {
            line = line.substring(1);
        }

        // Convert gitignore pattern to glob
        String globPattern = convertToGlob(line);

        try {
            PathMatcher matcher = projectRoot.getFileSystem().getPathMatcher("glob:" + globPattern);
            if (isNegate) {
                negatePatterns.add(matcher);
            } else {
                ignorePatterns.add(matcher);
            }
        } catch (Exception e) {
            log.debug("Cannot parse gitignore rule: {}", line);
        }
    }

    /**
     * Converts a gitignore pattern to a glob pattern.
     */
    private String convertToGlob(String pattern) {
        // Strip leading slash
        if (pattern.startsWith("/")) {
            pattern = pattern.substring(1);
        }

        // Trailing slash means directory
        boolean isDirectory = pattern.endsWith("/");
        if (isDirectory) {
            pattern = pattern.substring(0, pattern.length() - 1);
        }

        // Build glob
        StringBuilder glob = new StringBuilder("**");

        // If not already **-prefixed, add path separator
        if (!pattern.startsWith("**")) {
            glob.append("/");
        }

        glob.append(pattern);

        // Directory pattern matches the directory and everything under it
        if (isDirectory) {
            glob.append("/**");
        }

        return glob.toString();
    }

    /**
     * Adds default ignore patterns when no {@code .gitignore} exists.
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
                log.debug("Cannot add default rule: {}", pattern);
            }
        }
    }

    /**
     * Returns whether the path should be ignored.
     *
     * @param path path to check
     * @return true if ignored
     */
    public boolean isIgnored(Path path) {
        // Relative path from project root
        Path relativePath;
        try {
            relativePath = projectRoot.relativize(path);
        } catch (Exception e) {
            // Fall back to absolute path
            relativePath = path;
        }

        // Negation rules (!)
        for (PathMatcher matcher : negatePatterns) {
            if (matcher.matches(relativePath)) {
                return false; // explicitly not ignored
            }
        }

        // Ignore rules
        for (PathMatcher matcher : ignorePatterns) {
            if (matcher.matches(relativePath)) {
                return true; // ignored
            }
        }

        return false; // not ignored
    }
}
