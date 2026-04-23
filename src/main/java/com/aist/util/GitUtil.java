package com.aist.util;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Git utility class.
 * Executes Git commands and returns the results.
 */
@Slf4j
public class GitUtil {

    /**
     * Default command execution timeout (seconds).
     */
    private static final int DEFAULT_TIMEOUT = 60;

    /**
     * Result of a Git command execution.
     */
    @Data
    public static class GitCommandResult {
        /**
         * Exit code; 0 indicates success.
         */
        private int exitCode;

        /**
         * Standard output.
         */
        private String output;

        /**
         * Standard error output.
         */
        private String errorOutput;

        /**
         * Whether execution succeeded.
         */
        private boolean success;

        public GitCommandResult(int exitCode, String output, String errorOutput) {
            this.exitCode = exitCode;
            this.output = output;
            this.errorOutput = errorOutput;
            this.success = (exitCode == 0);
        }
    }


    /**
     * Executes a Git command in the given working directory (default timeout).
     *
     * @param workDir  Git repository working directory
     * @param commands Git command arguments, e.g. "log", "--oneline", "-n", "10"
     * @return command execution result
     */
    public static GitCommandResult executeGitCommand(String workDir, String... commands) {
        return executeGitCommand(workDir, DEFAULT_TIMEOUT, commands);
    }

    /**
     * Executes a Git command in the given working directory.
     *
     * @param workDir  Git repository working directory
     * @param timeout  timeout in seconds
     * @param commands Git command arguments, e.g. "log", "--oneline", "-n", "10"
     * @return command execution result
     */
    public static GitCommandResult executeGitCommand(String workDir, int timeout, String... commands) {
        if (commands == null || commands.length == 0) {
            throw new IllegalArgumentException("Git command cannot be empty");
        }

        File workDirectory = new File(workDir);
        if (!workDirectory.exists() || !workDirectory.isDirectory()) {
            throw new IllegalArgumentException("Working directory does not exist or is not a valid directory: " + workDir);
        }

        try {
            // Build the full Git command
            List<String> command = buildGitCommand(commands);

            log.info("Executing Git command: {} (working directory: {})", String.join(" ", command), workDir);

            // Create process
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.directory(workDirectory);

            // Start process
            Process process = processBuilder.start();

            // Read output — use system default charset (e.g. GBK on Chinese Windows)
            StringBuilder output = new StringBuilder();
            StringBuilder errorOutput = new StringBuilder();

            // System default charset
            String charset = System.getProperty("file.encoding", "UTF-8");

            // Read stdout
            Thread outputThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), charset))) {
                    String line;
                    int lineCount = 0;
                    while ((line = reader.readLine()) != null) {
                        output.append(line).append("\n");
                        lineCount++;
                    }
                    log.debug("Finished reading Git stdout, {} lines", lineCount);
                } catch (IOException e) {
                    log.error("Failed to read Git stdout", e);
                }
            });

            // Read stderr
            Thread errorThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getErrorStream(), charset))) {
                    String line;
                    int lineCount = 0;
                    while ((line = reader.readLine()) != null) {
                        errorOutput.append(line).append("\n");
                        lineCount++;
                        log.debug("Git stderr [{}]: {}", lineCount, line);
                    }
                    log.debug("Finished reading Git stderr, {} lines", lineCount);
                } catch (IOException e) {
                    log.error("Failed to read Git stderr", e);
                }
            });

            outputThread.start();
            errorThread.start();

            // Wait for process
            boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                log.error("Git command timed out");
                throw new RuntimeException("Git command timed out");
            }

            log.debug("Process finished, exit code: {}", process.exitValue());

            // Wait for reader threads (no timeout, ensure full read)
            outputThread.join();
            errorThread.join();

            log.debug("Reader threads finished");

            int exitCode = process.exitValue();
            String outputStr = output.toString().trim();
            String errorOutputStr = errorOutput.toString().trim();

            if (exitCode == 0) {
                log.info("Git command succeeded, output length: {} characters", outputStr.length());
            } else {
                log.warn("Git command failed, exit code: {}, error: {}", exitCode, errorOutputStr);
            }

            return new GitCommandResult(exitCode, outputStr, errorOutputStr);

        } catch (IOException e) {
            log.error("Failed to execute Git command", e);
            throw new RuntimeException("Failed to execute Git command: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Git command execution interrupted", e);
            throw new RuntimeException("Git command execution interrupted", e);
        }
    }

    /**
     * Builds the Git command list.
     *
     * @param commands Git command arguments
     * @return full command list
     */
    private static List<String> buildGitCommand(String... commands) {
        List<String> command = new ArrayList<>();

        // Invoke git directly; do not wrap with cmd /c
        // If git is on PATH, the JVM can resolve it
        command.add("git");
        Collections.addAll(command, commands);

        log.debug("Built command: {}", command);
        return command;
    }


    /**
     * Returns the combined diff for multiple commits.
     *
     * @param workDir   Git repository working directory
     * @param commitIds list of commit IDs
     * @return combined diff text for all commits
     */
    public static String getCommitsDiff(String workDir, List<String> commitIds) {
        return getCommitsDiff(workDir, commitIds, DEFAULT_TIMEOUT);
    }

    /**
     * Returns the combined diff for multiple commits (per-commit timeout).
     */
    public static String getCommitsDiff(String workDir, List<String> commitIds, int timeoutSecondsPerCommit) {
        return getCommitsDiff(workDir, commitIds, timeoutSecondsPerCommit, null);
    }

    /**
     * Returns the combined diff for multiple commits; if {@code failedCommitIdsOut} is non-null,
     * commit IDs for which {@code git show} failed or threw are appended to that list.
     */
    public static String getCommitsDiff(String workDir, List<String> commitIds, int timeoutSecondsPerCommit,
                                        List<String> failedCommitIdsOut) {
        if (commitIds == null || commitIds.isEmpty()) {
            throw new IllegalArgumentException("Commit ID list cannot be empty");
        }

        StringBuilder allDiffs = new StringBuilder();

        for (String commitId : commitIds) {
            try {
                GitCommandResult result = executeGitCommand(workDir, timeoutSecondsPerCommit, "show", commitId, "--");
                if (result.isSuccess()) {
                    allDiffs.append("=== 提交ID: ").append(commitId).append(" ===\n");
                    allDiffs.append(result.getOutput()).append("\n\n");
                } else {
                    log.warn("Failed to get diff for commit {}: {}", commitId, result.getErrorOutput());
                    if (failedCommitIdsOut != null) {
                        failedCommitIdsOut.add(commitId);
                    }
                    allDiffs.append("=== 提交ID: ").append(commitId).append(" (获取失败) ===\n");
                    allDiffs.append("错误: ").append(result.getErrorOutput()).append("\n\n");
                }
            } catch (Exception e) {
                log.error("Exception while processing commit {}", commitId, e);
                if (failedCommitIdsOut != null) {
                    failedCommitIdsOut.add(commitId);
                }
                allDiffs.append("=== 提交ID: ").append(commitId).append(" (异常) ===\n");
                allDiffs.append("异常: ").append(e.getMessage()).append("\n\n");
            }
        }

        return allDiffs.toString();
    }


    private static final Pattern SHORTSTAT_INSERTIONS =
            Pattern.compile("(\\d+) insertions?\\(\\+\\)");
    private static final Pattern SHORTSTAT_DELETIONS =
            Pattern.compile("(\\d+) deletions?\\(-\\)");

    /**
     * Parses the sum of inserted and deleted lines from {@code git diff/show --shortstat} output.
     */
    public static int parseShortStatTotalLines(String shortStatBlock) {
        if (shortStatBlock == null || shortStatBlock.isEmpty()) {
            return 0;
        }
        int ins = 0;
        int del = 0;
        Matcher mi = SHORTSTAT_INSERTIONS.matcher(shortStatBlock);
        if (mi.find()) {
            ins = Integer.parseInt(mi.group(1));
        }
        Matcher md = SHORTSTAT_DELETIONS.matcher(shortStatBlock);
        if (md.find()) {
            del = Integer.parseInt(md.group(1));
        }
        return ins + del;
    }

}
