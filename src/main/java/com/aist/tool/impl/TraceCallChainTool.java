package com.aist.tool.impl;

import com.aist.dto.MethodInfo;
import com.aist.dto.CodeAnalyzeContextDTO;
import com.aist.service.ProjectParseService;
import com.aist.tool.ToolRequest;
import com.aist.tool.ToolResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Bidirectional call-chain tracing: forward, backward, or both.
 */
@Slf4j
@Component
public class TraceCallChainTool extends AbstractTool {

    @Autowired
    private ProjectParseService projectParseService;

    @Override
    public String getName() {
        return "TRACE_CALL_CHAIN";
    }

    @Override
    public String getDescription() {
        return "双向调用链追踪（支持正向、反向、完整链路）";
    }

    @Override
    public String getParameterDescription() {
        return "方向:类名.方法名:层数 (方向: forward/backward/full)";
    }

    @Override
    public List<String> getExamples() {
        return List.of(
                "[TOOL_CALL:TRACE_CALL_CHAIN:forward:UserService.login:3]    # forward, depth 3",
                "[TOOL_CALL:TRACE_CALL_CHAIN:backward:OrderService.create:2] # backward, depth 2",
                "[TOOL_CALL:TRACE_CALL_CHAIN:full:PaymentService.pay:2]      # both ways, depth 2"
        );
    }

    @Override
    public String getUsageScenario() {
        return "追踪方法调用链，分析业务流程和依赖关系";
    }

    @Override
    public String getCapabilities() {
        return """
                支持：正向追踪（我调用了谁）、反向追踪（谁调用了我）、双向完整链路（既包含谁调用了我，也包含我调用了谁）
                特点：树形结构展示、支持多层追踪、标注分层信息
                注意：
                - 层数建议不超过5层，避免结果过大
                - 在进行调用链分析时，需要考虑运行时配置和设计模式的影响
                """;
    }

    @Override
    public int getPriority() {
        return 9; // high priority for call-chain questions
    }

    @Override
    protected String validateRequest(ToolRequest request) {
        if (!request.hasArguments() || request.getArguments().size() < 3) {
            return """
                    参数格式错误！
                    
                    正确格式: [TOOL_CALL:TRACE_CALL_CHAIN:方向:类名.方法名:层数]
                    
                    正确示例:
                      [TOOL_CALL:TRACE_CALL_CHAIN:forward:UserService.login:3]  # 正向追踪3层
                      [TOOL_CALL:TRACE_CALL_CHAIN:backward:OrderService.create:2]  # 反向追踪2层
                      [TOOL_CALL:TRACE_CALL_CHAIN:full:PaymentService.pay:2]  # 完整链路2层
                    
                    参数说明:
                      - 方向: forward（正向）/ backward（反向）/ full（双向）
                      - 类名.方法名: 如 UserService.login
                      - 层数: 追踪深度，建议2-5层
                    
                    错误示例:
                      [TOOL_CALL:TRACE_CALL_CHAIN:UserService.login:3]  #  缺少方向参数
                      [TOOL_CALL:TRACE_CALL_CHAIN:forward:UserService:3]  #  缺少方法名
                    """;
        }

        String direction = request.getArguments().get(0).toLowerCase();
        if (!direction.equals("forward") && !direction.equals("backward") && !direction.equals("full")) {
            return """
                    方向参数错误！必须是 forward、backward 或 full
                    正确示例:
                      [TOOL_CALL:TRACE_CALL_CHAIN:forward:UserService.login:3]
                      [TOOL_CALL:TRACE_CALL_CHAIN:backward:UserService.login:3]
                      [TOOL_CALL:TRACE_CALL_CHAIN:full:UserService.login:3]
                    """;
        }

        return null;
    }

    @Override
    protected ToolResult doExecute(ToolRequest request, CodeAnalyzeContextDTO context) {
        try {
            projectParseService.ensureProjectParsed(context);
        } catch (Exception e) {
            return ToolResult.error(getName(), request.getArgumentsString(),
                    "项目解析失败: " + e.getMessage());
        }

        String direction = request.getArguments().get(0).toLowerCase();
        String methodKey = request.getArguments().get(1);
        int maxDepth;

        try {
            maxDepth = Integer.parseInt(request.getArguments().get(2));
            if (maxDepth < 1 || maxDepth > 10) {
                return ToolResult.error(getName(), request.getArgumentsString(),
                        "层数必须在 1-10 之间");
            }
        } catch (NumberFormatException e) {
            return ToolResult.error(getName(), request.getArgumentsString(),
                    "层数参数必须是数字");
        }

        log.info("TRACE_CALL_CHAIN: direction={}, method={}, depth={}", direction, methodKey, maxDepth);

        MethodInfo targetMethod = findMethod(methodKey, context);
        if (targetMethod == null) {
            return ToolResult.notFound(getName(), methodKey,
                    "未找到方法: " + methodKey + "\n提示: 请使用格式 '类名.方法名' 或 '方法名'");
        }

        StringBuilder result = new StringBuilder();
        result.append("## 调用链追踪分析\n\n");
        result.append("**目标方法**: `").append(targetMethod.getFullClassName())
                .append(".").append(targetMethod.getMethodName()).append("`\n");
        result.append("**文件位置**: `").append(targetMethod.getFilePath()).append("`\n");
        if (targetMethod.getApiMapping() != null && !targetMethod.getApiMapping().isEmpty()) {
            result.append("**API 路径**: `").append(targetMethod.getApiMapping()).append("`\n");
        }
        result.append("**追踪方向**: ").append(getDirectionName(direction)).append("\n");
        result.append("**追踪层数**: ").append(maxDepth).append("\n\n");

        switch (direction) {
            case "forward":
                result.append(traceForward(targetMethod, maxDepth, context));
                break;
            case "backward":
                result.append(traceBackward(targetMethod, maxDepth, context));
                break;
            case "full":
                result.append(traceFull(targetMethod, maxDepth, context));
                break;
        }

        return ToolResult.success(getName(), request.getArgumentsString(), result.toString());
    }

    private String getDirectionName(String direction) {
        return switch (direction) {
            case "forward" -> "正向追踪（该方法调用了哪些方法）";
            case "backward" -> "反向追踪（哪些方法调用了该方法）";
            case "full" -> "双向完整链路（既包含谁调用了我，也包含我调用了谁）";
            default -> direction;
        };
    }

    /**
     * Forward: callees of {@code method}.
     */
    private String traceForward(MethodInfo method, int maxDepth, CodeAnalyzeContextDTO context) {
        StringBuilder result = new StringBuilder();
        result.append("### 正向调用链（该方法调用了哪些方法）\n\n");

        Set<String> visited = new HashSet<>();
        traceForwardRecursive(method, 0, maxDepth, "", visited, result, context);

        return result.toString();
    }

    private void traceForwardRecursive(MethodInfo method, int currentDepth, int maxDepth,
                                       String prefix, Set<String> visited,
                                       StringBuilder result, CodeAnalyzeContextDTO context) {
        String methodKey = method.getFullClassName() + "." + method.getMethodName();

        if (visited.contains(methodKey)) {
            result.append(prefix).append("└─ `").append(methodKey).append("` [循环引用]\n");
            return;
        }

        visited.add(methodKey);

        String layerTag = getLayerTag(method);
        result.append(prefix).append("└─ `").append(methodKey).append("`");
        if (layerTag != null) {
            result.append(" [").append(layerTag).append("]");
        }
        if (method.getApiMapping() != null && !method.getApiMapping().isEmpty()) {
            result.append(" - API: `").append(method.getApiMapping()).append("`");
        }
        result.append("\n");
        result.append(prefix).append("   📁 `").append(method.getFilePath())
                .append("` (行 ").append(method.getStartLine()).append("-")
                .append(method.getEndLine()).append(")\n");

        if (currentDepth < maxDepth && method.getCalledMethods() != null && !method.getCalledMethods().isEmpty()) {
            List<String> calledMethods = method.getCalledMethods();
            for (String calledMethodKey : calledMethods) {
                MethodInfo calledMethod = findMethodByKey(calledMethodKey, context);

                if (calledMethod != null) {
                    String newPrefix = prefix + "   ";
                    traceForwardRecursive(calledMethod, currentDepth + 1, maxDepth, newPrefix, visited, result, context);
                } else {
                    result.append(prefix).append("   └─ `").append(calledMethodKey).append("` [外部方法或未解析]\n");
                }
            }
        }
    }

    /**
     * Backward: callers of {@code method}.
     */
    private String traceBackward(MethodInfo method, int maxDepth, CodeAnalyzeContextDTO context) {
        StringBuilder result = new StringBuilder();
        result.append("### 反向调用链（哪些方法调用了该方法）\n\n");

        Set<String> visited = new HashSet<>();
        traceBackwardRecursive(method, maxDepth, visited, result, context);

        return result.toString();
    }

    private void traceBackwardRecursive(MethodInfo method, int maxDepth,
                                        Set<String> visited,
                                        StringBuilder result, CodeAnalyzeContextDTO context) {
        traceBackwardRecursiveWithCallee(method, null, 0, maxDepth, "", visited, result, context);
    }

    /**
     * Backward recursion; {@code callee} is the child for call-site extraction (null at root).
     */
    private void traceBackwardRecursiveWithCallee(MethodInfo method, MethodInfo callee, int currentDepth, int maxDepth,
                                                  String prefix, Set<String> visited,
                                                  StringBuilder result, CodeAnalyzeContextDTO context) {
        String methodKey = method.getFullClassName() + "." + method.getMethodName();

        if (visited.contains(methodKey)) {
            result.append(prefix).append("└─ `").append(methodKey).append("` [循环引用]\n");
            return;
        }

        visited.add(methodKey);

        String layerTag = getLayerTag(method);
        result.append(prefix).append("└─ `").append(methodKey).append("`");
        if (layerTag != null) {
            result.append(" [").append(layerTag).append("]");
        }
        if (method.getApiMapping() != null && !method.getApiMapping().isEmpty()) {
            result.append(" - API: `").append(method.getApiMapping()).append("`");
        }
        result.append("\n");
        result.append(prefix).append("   📁 `").append(method.getFilePath())
                .append("` (行 ").append(method.getStartLine()).append("-")
                .append(method.getEndLine()).append(")\n");

        if (callee != null) {
            String callSiteCode = extractCallSiteCode(method, callee);
            if (callSiteCode != null && !callSiteCode.isEmpty()) {
                result.append(prefix).append("   📍 调用点代码:\n");
                for (String line : callSiteCode.split("\n")) {
                    result.append(prefix).append("      ").append(line).append("\n");
                }
            }
        }

        if (currentDepth < maxDepth && method.getCalledBy() != null && !method.getCalledBy().isEmpty()) {
            List<String> callers = method.getCalledBy();
            for (String callerKey : callers) {
                MethodInfo caller = findMethodByKey(callerKey, context);

                if (caller != null) {
                    String newPrefix = prefix + "   ";
                    traceBackwardRecursiveWithCallee(caller, method, currentDepth + 1, maxDepth, newPrefix, visited, result, context);
                } else {
                    result.append(prefix).append("   └─ `").append(callerKey).append("` [未找到]\n");
                }
            }
        }
    }

    /**
     * Snippet around the first call to {@code callee} inside {@code caller}'s body.
     */
    private String extractCallSiteCode(MethodInfo caller, MethodInfo callee) {
        if (caller.getMethodBody() == null || caller.getMethodBody().isEmpty()) {
            return null;
        }

        String calleeMethodName = callee.getMethodName();
        String[] lines = caller.getMethodBody().split("\n");
        List<String> resultLines = new ArrayList<>();

        int contextLinesBefore = 2;
        int contextLinesAfter = 1;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (containsMethodCall(line, calleeMethodName)) {
                int start = Math.max(0, i - contextLinesBefore);
                int end = Math.min(lines.length - 1, i + contextLinesAfter);

                for (int j = start; j <= end; j++) {
                    String contextLine = lines[j].trim();
                    if (!contextLine.isEmpty() && !contextLine.startsWith("//") && !contextLine.startsWith("/*") && !contextLine.startsWith("*")) {
                        resultLines.add(contextLine);
                    }
                }
                break;  // first call site only
            }
        }

        return resultLines.isEmpty() ? null : String.join("\n", resultLines);
    }

    /**
     * Heuristic: line contains an invocation of {@code methodName}(…).
     */
    private boolean containsMethodCall(String line, String methodName) {
        String dotPattern = "\\." + methodName + "\\s*\\(";
        if (line.matches(".*" + dotPattern + ".*")) {
            return true;
        }

        String generalPattern = "[^a-zA-Z0-9_]" + methodName + "\\s*\\(";
        if (line.matches(".*" + generalPattern + ".*")) {
            return true;
        }

        if (line.trim().startsWith(methodName + "(")) {
            return true;
        }

        return false;
    }

    /** Renders backward then forward. */
    private String traceFull(MethodInfo method, int maxDepth, CodeAnalyzeContextDTO context) {
        return traceBackward(method, maxDepth, context) +
                "\n" +
                traceForward(method, maxDepth, context);
    }

    private MethodInfo findMethod(String methodKey, CodeAnalyzeContextDTO context) {
        String searchKey = methodKey.toLowerCase().trim();
        String targetClassName = "";
        String targetMethodName = searchKey;

        if (searchKey.contains(".")) {
            int lastDot = searchKey.lastIndexOf(".");
            targetClassName = searchKey.substring(0, lastDot);
            targetMethodName = searchKey.substring(lastDot + 1);
        }

        List<MethodInfo> candidates = new ArrayList<>();
        for (MethodInfo method : context.getAllMethods()) {
            String className = method.getClassName().toLowerCase();
            String fullClassName = method.getFullClassName().toLowerCase();
            String methodName = method.getMethodName().toLowerCase();

            if (methodName.equals(className) && !targetMethodName.equals(className)) {
                continue;
            }

            if (methodName.equals(targetMethodName)) {
                if (targetClassName.isEmpty()) {
                    candidates.add(method);
                } else {
                    if (className.equals(targetClassName)) {
                        candidates.add(0, method);
                    } else if (fullClassName.endsWith("." + targetClassName)) {
                        candidates.add(method);
                    } else if (className.contains(targetClassName) || fullClassName.contains(targetClassName)) {
                        candidates.add(method);
                    }
                }
            }
        }

        return candidates.isEmpty() ? null : candidates.get(0);
    }

    private MethodInfo findMethodByKey(String methodKey, CodeAnalyzeContextDTO context) {
        if (context.getMethodMap() != null) {
            return context.getMethodMap().get(methodKey);
        }
        return null;
    }


    /** Layer hint from package / name (English labels). */
    private String getLayerTag(MethodInfo method) {
        String fullClassName = method.getFullClassName().toLowerCase();

        if (fullClassName.contains(".controller.") || fullClassName.endsWith("controller")) {
            return "Controller层";
        } else if (fullClassName.contains(".service.") || fullClassName.endsWith("service")) {
            return "Service层";
        } else if (fullClassName.contains(".mapper.") || fullClassName.endsWith("mapper")) {
            return "Mapper层";
        } else if (fullClassName.contains(".dao.") || fullClassName.endsWith("dao")) {
            return "DAO层";
        } else if (fullClassName.contains(".util.") || fullClassName.endsWith("util")) {
            return "工具类";
        }

        return null;
    }

}

