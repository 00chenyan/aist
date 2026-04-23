package com.aist.tool.impl;

import com.alibaba.fastjson2.JSON;
import com.aist.dto.CodeAnalyzeContextDTO;
import com.aist.tool.ToolRequest;
import com.aist.tool.ToolResult;
import com.aist.util.GitUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Git变更查询工具
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GitDiffTool extends AbstractTool {

    @Override
    public String getName() {
        return "GIT";
    }

    @Override
    public String getDescription() {
        return "Git提交记录查询和代码变更分析";
    }

    @Override
    public String getParameterDescription() {
        return "子命令:参数 (仅支持 search/files/show/content)";
    }

    @Override
    public List<String> getExamples() {
        return List.of(
                "[TOOL_CALL:GIT:search:#2508055]",
                "[TOOL_CALL:GIT:files:8719c258]",
                "[TOOL_CALL:GIT:show:8719c258:go-hire-sale-provider/src/main/java/net/poweroak/saas/blugo/controller/app/AppRepertoryController.java]",
                "[TOOL_CALL:GIT:content:8719c258:go-hire-sale-provider/src/main/java/net/poweroak/saas/blugo/controller/app/AppRepertoryController.java]"
        );
    }

    @Override
    public String getUsageScenario() {
        return "分析需求/缺陷对应的代码提交、查看提交修改的文件、查看代码变更详情";
    }

    @Override
    public String getCapabilities() {
        return """
                【标准工作流程】分析需求/缺陷代码变更时，严格按以下4步执行：
                
                步骤1：search:关键词
                  - 命令：git log --grep=关键词
                  - 返回：commitId列表（如 8719c258）
                  - 示例：[TOOL_CALL:GIT:search:#2508055]
                
                步骤2：files:commitId
                  - 命令：git diff-tree --name-only commitId
                  - 返回：修改的文件列表（完整路径，无diff内容）
                  - 示例：[TOOL_CALL:GIT:files:8719c258]
                  - ⚠️ commitId必须是步骤1返回的真实值
                  - ⚠️ 不要添加任何额外参数
                
                步骤3：show:commitId:完整文件路径
                  - 命令：git show commitId -- 文件路径
                  - 返回：该文件的代码diff（+增加 -删除）
                  - 示例：[TOOL_CALL:GIT:show:8719c258]
                  - ⚠️ 必须使用步骤2返回的完整文件路径，一个字符都不能改
                  - ⚠️ 不要简写文件名，必须复制粘贴完整路径
                
                步骤4（可选）：content:commitId:完整文件路径
                  - 命令：git show commitId:文件路径
                  - 返回：该文件在该提交时的完整源代码
                  - 示例：[TOOL_CALL:GIT:content:8719c258]
                  - ⚠️ 必须使用步骤2返回的完整文件路径
                  - 用途：查看文件完整内容，而不是只看diff
                
                【严格禁止的错误用法】
                ❌ [TOOL_CALL:GIT:files:8719c258:.]                    # 不要添加"."
                ❌ [TOOL_CALL:GIT:files:8719c258:列出所有文件]          # 不要添加中文说明
                ❌ [TOOL_CALL:GIT:show:8719c258]                       # show必须指定完整文件路径
                ❌ [TOOL_CALL:GIT:show:8719c258:文件名]                # 不要使用占位符
                ❌ [TOOL_CALL:GIT:show:6e4e4e4e:xxx.java]              # 不要编造commitId

                【重要约束】
                1. commitId必须来自search命令的返回结果，不能编造
                2. 文件路径必须来自files命令的返回结果，不能编造
                3. 不要使用"."、"*"、"文件名"等占位符或通配符
                4. 不要在参数中添加中文说明或注释
                5. 只能使用英文冒号":"分隔参数
                """;
    }

    @Override
    public int getPriority() {
        return 50; // Git 操作优先级最低
    }

    @Override
    protected ToolResult doExecute(ToolRequest request, CodeAnalyzeContextDTO context) {
        List<String> arguments = request.getArguments();
        log.info("GitDiffTool arguments: {}", JSON.toJSONString(arguments));

        if (arguments == null || arguments.isEmpty()) {
            return ToolResult.error(getName(), "", "请指定Git子命令，仅支持: search, files, show, content");
        }

        // 预处理参数：只移除空参数，保留其他所有参数
        List<String> cleanArgs = new ArrayList<>();
        for (String arg : arguments) {
            if (arg == null) continue;
            arg = arg.trim();
            if (arg.isEmpty()) continue;
            cleanArgs.add(arg);
        }

        if (cleanArgs.isEmpty()) {
            return ToolResult.error(getName(), "", "请指定Git子命令，仅支持: search, files, show, content");
        }

        String subCommand = cleanArgs.get(0).toLowerCase();
        List<String> params = cleanArgs.size() > 1 ? cleanArgs.subList(1, cleanArgs.size()) : new ArrayList<>();
        log.info("执行Git命令: {} 参数数量: {}", subCommand, params.size());

        try {
            GitUtil.GitCommandResult result;
            StringBuilder output = new StringBuilder();

            switch (subCommand) {
                case "search":
                    // 步骤1：搜索提交记录
                    if (params.isEmpty()) {
                        return ToolResult.error(getName(), subCommand, "请指定搜索关键词（如 #2508055）");
                    }
                    String keyword = String.join(" ", params);
                    result = GitUtil.executeGitCommand(context.getProjectPath(),
                            "log", "--oneline", "--all", "-i", "--grep=" + keyword, "-n", "100");

                    // 检查是否找到提交记录
                    if (result.isSuccess() && (result.getOutput() == null || result.getOutput().trim().isEmpty())) {
                        return ToolResult.success(getName(), subCommand,
                                "**未找到包含关键词 \"" + keyword + "\" 的提交记录**\n\n" +
                                "可能的原因：\n" +
                                "1. 关键词拼写错误\n" +
                                "2. 该需求/缺陷尚未提交代码\n" +
                                "3. 提交记录超过100条（当前限制）\n\n" +
                                "建议：检查关键词是否正确，或尝试使用更精确的搜索词。");
                    }

                    output.append("**包含关键词 \"").append(keyword).append("\" 的提交记录**:\n\n");
                    output.append("提示：从下面的列表中选择commitId，然后使用 [TOOL_CALL:GIT:files:commitId] 查看修改的文件\n\n");
                    break;

                case "files":
                    // 步骤2：查看提交修改的文件列表
                    if (params.isEmpty()) {
                        return ToolResult.error(getName(), subCommand, "请指定commitId");
                    }
                    String filesCommitId = params.get(0);

                    // 验证commitId格式
                    if (!filesCommitId.matches("^[a-fA-F0-9]{6,40}$")) {
                        return ToolResult.error(getName(), subCommand, "无效的commitId格式: " + filesCommitId);
                    }

                    // 检查是否有多余参数
                    if (params.size() > 1) {
                        String extraParam = String.join(" ", params.subList(1, params.size()));
                        return ToolResult.error(getName(), subCommand,
                                "files命令不需要额外参数，检测到: \"" + extraParam + "\"\n" +
                                        "正确用法：[TOOL_CALL:GIT:files:" + filesCommitId + "]");
                    }

                    // 执行 git diff-tree 获取文件列表
                    result = GitUtil.executeGitCommand(context.getProjectPath(),
                            "diff-tree", "--no-commit-id", "--name-only", "-r", filesCommitId);

                    // 检查是否有文件修改
                    if (result.isSuccess() && (result.getOutput() == null || result.getOutput().trim().isEmpty())) {
                        return ToolResult.success(getName(), subCommand,
                                "**提交 " + filesCommitId + " 没有修改任何文件**\n\n可能是合并提交或空提交。");
                    }

                    output.append("**提交 ").append(filesCommitId).append(" 修改的文件列表**:\n\n");
                    output.append("提示：从下面的列表中复制完整路径，然后使用 [TOOL_CALL:GIT:show:").append(filesCommitId).append(":完整路径] 查看代码变更\n\n");
                    break;

                case "show":
                    // 步骤3：查看文件的代码diff
                    if (params.isEmpty()) {
                        return ToolResult.error(getName(), subCommand, "请指定commitId和完整文件路径");
                    }

                    String showCommitId = params.get(0);

                    // 验证commitId格式
                    if (!showCommitId.matches("^[a-fA-F0-9]{6,40}$")) {
                        return ToolResult.error(getName(), subCommand, "无效的commitId格式: " + showCommitId);
                    }

                    // show命令必须指定完整文件路径
                    if (params.size() < 2) {
                        return ToolResult.error(getName(), subCommand,
                                "show命令必须指定完整文件路径\n" +
                                        "提示：先使用 [TOOL_CALL:GIT:files:" + showCommitId + "] 查看文件列表\n" +
                                        "      然后复制完整路径");
                    }

                    // 文件路径从第二个参数开始，可能包含空格，所以需要拼接
                    String filePath = String.join(" ", params.subList(1, params.size())).trim();

                    // 检测常见的错误参数
                    if (filePath.equals(".") || filePath.equals("*") || filePath.equals(".*")) {
                        return ToolResult.error(getName(), subCommand,
                                "不支持通配符作为文件路径参数\n" +
                                        "提示：先使用 [TOOL_CALL:GIT:files:" + showCommitId + "] 查看文件列表\n" +
                                        "      然后复制完整路径");
                    }

                    // 检测中文字符（可能是占位符）
                    if (filePath.matches(".*[\\u4e00-\\u9fa5]+.*")) {
                        return ToolResult.error(getName(), subCommand,
                                "文件路径不能包含中文字符: \"" + filePath + "\"\n" +
                                        "提示：先使用 [TOOL_CALL:GIT:files:" + showCommitId + "] 查看文件列表\n" +
                                        "      然后复制完整路径");
                    }

                    // 直接使用文件路径，不进行自动补全
                    result = GitUtil.executeGitCommand(context.getProjectPath(),
                            "show", showCommitId, "--", filePath);
                    output.append("**提交 ").append(showCommitId).append(" 中文件 ").append(filePath).append(" 的代码变更**:\n\n");
                    break;

                case "content":
                    // 步骤4：查看文件在某次提交时的完整内容
                    if (params.isEmpty()) {
                        return ToolResult.error(getName(), subCommand, "请指定commitId和完整文件路径");
                    }

                    String contentCommitId = params.get(0);

                    // 验证commitId格式
                    if (!contentCommitId.matches("^[a-fA-F0-9]{6,40}$")) {
                        return ToolResult.error(getName(), subCommand, "无效的commitId格式: " + contentCommitId);
                    }

                    // content命令必须指定完整文件路径
                    if (params.size() < 2) {
                        return ToolResult.error(getName(), subCommand,
                                "content命令必须指定完整文件路径\n" +
                                        "提示：先使用 [TOOL_CALL:GIT:files:" + contentCommitId + "] 查看文件列表\n" +
                                        "      然后复制完整路径");
                    }

                    // 文件路径从第二个参数开始，可能包含空格，所以需要拼接
                    String contentFilePath = String.join(" ", params.subList(1, params.size())).trim();

                    // 检测常见的错误参数
                    if (contentFilePath.equals(".") || contentFilePath.equals("*") || contentFilePath.equals(".*")) {
                        return ToolResult.error(getName(), subCommand,
                                "不支持通配符作为文件路径参数\n" +
                                        "提示：先使用 [TOOL_CALL:GIT:files:" + contentCommitId + "] 查看文件列表\n" +
                                        "      然后复制完整路径");
                    }

                    // 检测中文字符（可能是占位符）
                    if (contentFilePath.matches(".*[\\u4e00-\\u9fa5]+.*")) {
                        return ToolResult.error(getName(), subCommand,
                                "文件路径不能包含中文字符: \"" + contentFilePath + "\"\n" +
                                        "提示：先使用 [TOOL_CALL:GIT:files:" + contentCommitId + "] 查看文件列表\n" +
                                        "      然后复制完整路径");
                    }

                    // 直接使用文件路径，不进行自动补全
                    // 执行 git show commitId:文件路径
                    result = GitUtil.executeGitCommand(context.getProjectPath(),
                            "show", contentCommitId + ":" + contentFilePath);
                    output.append("**提交 ").append(contentCommitId).append(" 时文件 ").append(contentFilePath).append(" 的完整内容**:\n\n");
                    break;

                default:
                    return ToolResult.error(getName(), subCommand,
                            "不支持的Git子命令: " + subCommand + "\n仅支持: search, files, show, content");
            }

            if (result.isSuccess()) {
                String gitOutput = result.getOutput();

                // 针对不同命令使用不同的输出限制
                int maxLength;
                switch (subCommand) {
                    case "search":
                    case "files":
                        maxLength = 30000;  // 搜索结果和文件列表通常较短
                        break;
                    case "show":
                        maxLength = 50000;  // diff 内容可能较长
                        break;
                    case "content":
                        maxLength = 80000;  // 完整文件内容可能很长
                        break;
                    default:
                        maxLength = 30000;
                }

                if (gitOutput.length() > maxLength) {
                    gitOutput = gitOutput.substring(0, maxLength) +
                            "\n\n... (输出过长，已截断。原始长度: " + gitOutput.length() + " 字符)";
                }

                output.append("```\n").append(gitOutput).append("\n```");
                return ToolResult.success(getName(), String.join(",", cleanArgs), output.toString());
            } else {
                return ToolResult.error(getName(), String.join(",", cleanArgs),
                        "Git命令执行失败: " + result.getErrorOutput());
            }

        } catch (Exception e) {
            return ToolResult.error(getName(), String.join(",", cleanArgs), "执行Git命令异常: " + e.getMessage());
        }
    }
}

