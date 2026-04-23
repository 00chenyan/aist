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
 * Git 工具类
 * 用于执行 Git 命令并获取执行结果
 */
@Slf4j
public class GitUtil {

    /**
     * 默认命令执行超时时间(秒)
     */
    private static final int DEFAULT_TIMEOUT = 60;

    /**
     * Git 命令执行结果
     */
    @Data
    public static class GitCommandResult {
        /**
         * 退出码，0表示成功
         */
        private int exitCode;

        /**
         * 标准输出内容
         */
        private String output;

        /**
         * 错误输出内容
         */
        private String errorOutput;

        /**
         * 是否执行成功
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
     * 在指定工作目录执行 Git 命令（使用默认超时时间）
     *
     * @param workDir  Git 仓库工作目录
     * @param commands Git 命令参数，例如: "log", "--oneline", "-n", "10"
     * @return 命令执行结果
     */
    public static GitCommandResult executeGitCommand(String workDir, String... commands) {
        return executeGitCommand(workDir, DEFAULT_TIMEOUT, commands);
    }

    /**
     * 在指定工作目录执行 Git 命令
     *
     * @param workDir  Git 仓库工作目录
     * @param timeout  超时时间（秒）
     * @param commands Git 命令参数，例如: "log", "--oneline", "-n", "10"
     * @return 命令执行结果
     */
    public static GitCommandResult executeGitCommand(String workDir, int timeout, String... commands) {
        if (commands == null || commands.length == 0) {
            throw new IllegalArgumentException("Git 命令不能为空");
        }

        File workDirectory = new File(workDir);
        if (!workDirectory.exists() || !workDirectory.isDirectory()) {
            throw new IllegalArgumentException("工作目录不存在或不是有效目录: " + workDir);
        }

        try {
            // 构建完整的 Git 命令
            List<String> command = buildGitCommand(commands);

            log.info("执行 Git 命令: {} (工作目录: {})", String.join(" ", command), workDir);

            // 创建进程
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.directory(workDirectory);

            // 启动进程
            Process process = processBuilder.start();

            // 读取输出 - 使用系统默认字符集（Windows中文系统通常是GBK）
            StringBuilder output = new StringBuilder();
            StringBuilder errorOutput = new StringBuilder();

            // 获取系统默认字符集
            String charset = System.getProperty("file.encoding", "UTF-8");

            // 读取标准输出
            Thread outputThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), charset))) {
                    String line;
                    int lineCount = 0;
                    while ((line = reader.readLine()) != null) {
                        output.append(line).append("\n");
                        lineCount++;
                    }
                    log.debug("Git 标准输出读取完成，共 {} 行", lineCount);
                } catch (IOException e) {
                    log.error("读取 Git 输出失败", e);
                }
            });

            // 读取错误输出
            Thread errorThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getErrorStream(), charset))) {
                    String line;
                    int lineCount = 0;
                    while ((line = reader.readLine()) != null) {
                        errorOutput.append(line).append("\n");
                        lineCount++;
                        log.debug("Git 错误输出[{}]: {}", lineCount, line);
                    }
                    log.debug("Git 错误输出读取完成，共 {} 行", lineCount);
                } catch (IOException e) {
                    log.error("读取 Git 错误输出失败", e);
                }
            });

            outputThread.start();
            errorThread.start();

            // 等待进程完成
            boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                log.error("Git 命令执行超时");
                throw new RuntimeException("Git 命令执行超时");
            }

            log.debug("进程已结束，退出码: {}", process.exitValue());

            // 等待输出线程完全结束（不设置超时，确保读取完整）
            outputThread.join();
            errorThread.join();

            log.debug("输出线程已结束");

            int exitCode = process.exitValue();
            String outputStr = output.toString().trim();
            String errorOutputStr = errorOutput.toString().trim();

            if (exitCode == 0) {
                log.info("Git 命令执行成功，输出长度: {} 字符", outputStr.length());
            } else {
                log.warn("Git 命令执行失败，退出码: {}, 错误信息: {}", exitCode, errorOutputStr);
            }

            return new GitCommandResult(exitCode, outputStr, errorOutputStr);

        } catch (IOException e) {
            log.error("执行 Git 命令失败", e);
            throw new RuntimeException("执行 Git 命令失败: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Git 命令执行被中断", e);
            throw new RuntimeException("Git 命令执行被中断", e);
        }
    }

    /**
     * 构建 Git 命令
     *
     * @param commands Git 命令参数
     * @return 完整的命令列表
     */
    private static List<String> buildGitCommand(String... commands) {
        List<String> command = new ArrayList<>();

        // 直接调用 git 命令，不使用 cmd /c 包装
        // 如果 git 在系统 PATH 中，Java 可以直接找到
        command.add("git");
        Collections.addAll(command, commands);

        log.debug("构建的命令: {}", command);
        return command;
    }


    /**
     * 获取多个提交的代码差异
     *
     * @param workDir   Git 仓库工作目录
     * @param commitIds 提交ID列表
     * @return 所有提交的差异内容
     */
    public static String getCommitsDiff(String workDir, List<String> commitIds) {
        return getCommitsDiff(workDir, commitIds, DEFAULT_TIMEOUT);
    }

    /**
     * 获取多个提交的代码差异（可指定单提交命令超时）
     */
    public static String getCommitsDiff(String workDir, List<String> commitIds, int timeoutSecondsPerCommit) {
        return getCommitsDiff(workDir, commitIds, timeoutSecondsPerCommit, null);
    }

    /**
     * 获取多个提交的代码差异；若 {@code failedCommitIdsOut} 非空，会将 {@code git show} 非成功或抛异常的提交 id 追加到该列表。
     */
    public static String getCommitsDiff(String workDir, List<String> commitIds, int timeoutSecondsPerCommit,
                                        List<String> failedCommitIdsOut) {
        if (commitIds == null || commitIds.isEmpty()) {
            throw new IllegalArgumentException("提交ID列表不能为空");
        }

        StringBuilder allDiffs = new StringBuilder();

        for (String commitId : commitIds) {
            try {
                GitCommandResult result = executeGitCommand(workDir, timeoutSecondsPerCommit, "show", commitId, "--");
                if (result.isSuccess()) {
                    allDiffs.append("=== 提交ID: ").append(commitId).append(" ===\n");
                    allDiffs.append(result.getOutput()).append("\n\n");
                } else {
                    log.warn("获取提交 {} 差异失败: {}", commitId, result.getErrorOutput());
                    if (failedCommitIdsOut != null) {
                        failedCommitIdsOut.add(commitId);
                    }
                    allDiffs.append("=== 提交ID: ").append(commitId).append(" (获取失败) ===\n");
                    allDiffs.append("错误: ").append(result.getErrorOutput()).append("\n\n");
                }
            } catch (Exception e) {
                log.error("处理提交 {} 时发生异常", commitId, e);
                if (failedCommitIdsOut != null) {
                    failedCommitIdsOut.add(commitId);
                }
                allDiffs.append("=== 提交ID: ").append(commitId).append(" (异常) ===\n");
                allDiffs.append("异常: ").append(e.getMessage()).append("\n\n");
            }
        }

        return allDiffs.toString();
    }

    /**
     * 将Git差异内容保存到文件
     *
     * @param workDir    Git 仓库工作目录
     * @param commitIds  提交ID列表
     * @param outputFile 输出文件路径
     * @return 操作结果
     */
    public static String saveCommitsDiffToFile(String workDir, List<String> commitIds, String outputFile) {
        try {
            String diffContent = getCommitsDiff(workDir, commitIds);

            // 确保输出目录存在
            File file = new File(outputFile);
            File parentDir = file.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }

            // 写入文件
            try (FileWriter writer = new FileWriter(file, StandardCharsets.UTF_8)) {
                writer.write("Git代码变更分析\n");
                writer.write("生成时间: " + new Date() + "\n");
                writer.write("仓库路径: " + workDir + "\n");
                writer.write("提交数量: " + commitIds.size() + "\n");
                writer.write("提交列表: " + String.join(", ", commitIds) + "\n");
                writer.write("\n");
                // 使用字符串重复而不是repeat方法
                for (int i = 0; i < 100; i++) {
                    writer.write("=");
                }
                writer.write("\n\n");
                writer.write(diffContent);
            }

            log.info("Git差异已保存到文件: {}", file.getAbsolutePath());
            return "成功保存 " + commitIds.size() + " 个提交的差异到文件: " + file.getAbsolutePath();

        } catch (Exception e) {
            log.error("保存Git差异到文件失败", e);
            throw new RuntimeException("保存Git差异到文件失败: " + e.getMessage());
        }
    }

    private static final Pattern SHORTSTAT_INSERTIONS =
            Pattern.compile("(\\d+) insertions?\\(\\+\\)");
    private static final Pattern SHORTSTAT_DELETIONS =
            Pattern.compile("(\\d+) deletions?\\(-\\)");

    /**
     * 解析 {@code git diff/show --shortstat} 输出中的插入与删除行数之和。
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

    /**
     * 使用 git 统计单个提交的变更行数（插入 + 删除）。
     * 优先 {@code git diff --shortstat commit^ commit}，根提交时回退为 {@code git show --shortstat}。
     */
    public static int getCommitChangedLineCount(String workDir, String commitId) {
        if (commitId == null || commitId.trim().isEmpty()) {
            return 0;
        }
        String id = commitId.trim();
        try {
            GitCommandResult r = executeGitCommand(workDir, "diff", "--shortstat", id + "^", id);
            if (r.isSuccess()) {
                return parseShortStatTotalLines(r.getOutput());
            }
            GitCommandResult r2 = executeGitCommand(workDir, "show", "-s", "--shortstat", id);
            if (r2.isSuccess()) {
                return parseShortStatTotalLines(r2.getOutput());
            }
            log.warn("无法统计提交 {} 的变更行数: {}", id, r2.getErrorOutput());
        } catch (Exception e) {
            log.warn("统计提交 {} 变更行数异常: {}", id, e.getMessage());
        }
        return 0;
    }

    /**
     * 获取单次提交相对其父提交的补丁（优先 {@code git diff commit^ commit}，失败时回退 {@code git show --patch}）。
     */
    public static String getSingleCommitDiffPatch(String workDir, String commitId, int timeoutSeconds) {
        if (commitId == null || commitId.trim().isEmpty()) {
            return "";
        }
        String id = commitId.trim();
        try {
            GitCommandResult r1 = executeGitCommand(workDir, timeoutSeconds, "diff", id + "^", id);
            if (r1.isSuccess()) {
                return r1.getOutput();
            }
            log.debug("git diff {}^ {} 未成功，回退 git show: {}", id, id, r1.getErrorOutput());
            GitCommandResult r2 = executeGitCommand(workDir, timeoutSeconds, "show", "--patch", "--no-textconv", id);
            if (r2.isSuccess()) {
                return r2.getOutput();
            }
            return "（获取变更失败）\n" + r2.getErrorOutput();
        } catch (Exception e) {
            log.warn("getSingleCommitDiffPatch id={}: {}", id, e.getMessage());
            return "（获取变更异常）\n" + e.getMessage();
        }
    }
}
