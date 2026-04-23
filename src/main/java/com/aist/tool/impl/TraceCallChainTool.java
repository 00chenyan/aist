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
 * 双向调用链追踪工具
 * 支持正向追踪（我调用了谁）、反向追踪（谁调用了我）、完整链路追踪
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
                "[TOOL_CALL:TRACE_CALL_CHAIN:forward:UserService.login:3]    # 正向追踪3层",
                "[TOOL_CALL:TRACE_CALL_CHAIN:backward:OrderService.create:2] # 反向追踪2层",
                "[TOOL_CALL:TRACE_CALL_CHAIN:full:PaymentService.pay:2]      # 双向完整链路2层"
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
        return 9; // 调用链分析优先级较高
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
        // 自动确保项目已解析
        try {
            projectParseService.ensureProjectParsed(context);
        } catch (Exception e) {
            return ToolResult.error(getName(), request.getArgumentsString(),
                    "项目解析失败: " + e.getMessage());
        }

        String direction = request.getArguments().get(0).toLowerCase();
        String methodKey = request.getArguments().get(1);
        int maxDepth; // 默认3层

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

        log.info("追踪调用链: 方向={}, 方法={}, 层数={}", direction, methodKey, maxDepth);

        // 查找目标方法
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

        // 根据方向执行不同的追踪
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
     * 正向追踪：该方法调用了哪些方法
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

        // 防止循环调用
        if (visited.contains(methodKey)) {
            result.append(prefix).append("└─ `").append(methodKey).append("` [循环引用]\n");
            return;
        }

        visited.add(methodKey);

        // 输出当前方法
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

        // 如果还没到最大深度，继续追踪
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
     * 反向追踪：哪些方法调用了该方法
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
     * 反向追踪递归实现（带被调用方法信息）
     *
     * @param method 当前方法
     * @param callee 被当前方法调用的方法（用于提取调用点代码），第一层时为null
     */
    private void traceBackwardRecursiveWithCallee(MethodInfo method, MethodInfo callee, int currentDepth, int maxDepth,
                                                  String prefix, Set<String> visited,
                                                  StringBuilder result, CodeAnalyzeContextDTO context) {
        String methodKey = method.getFullClassName() + "." + method.getMethodName();

        // 防止循环调用
        if (visited.contains(methodKey)) {
            result.append(prefix).append("└─ `").append(methodKey).append("` [循环引用]\n");
            return;
        }

        visited.add(methodKey);

        // 输出当前方法
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

        // 如果有被调用方法，提取并展示调用点代码
        if (callee != null) {
            String callSiteCode = extractCallSiteCode(method, callee);
            if (callSiteCode != null && !callSiteCode.isEmpty()) {
                result.append(prefix).append("   📍 调用点代码:\n");
                for (String line : callSiteCode.split("\n")) {
                    result.append(prefix).append("      ").append(line).append("\n");
                }
            }
        }

        // 如果还没到最大深度，继续追踪
        if (currentDepth < maxDepth && method.getCalledBy() != null && !method.getCalledBy().isEmpty()) {
            List<String> callers = method.getCalledBy();
            for (String callerKey : callers) {
                MethodInfo caller = findMethodByKey(callerKey, context);

                if (caller != null) {
                    String newPrefix = prefix + "   ";
                    // 传递当前方法作为被调用方法，以便提取调用点代码
                    traceBackwardRecursiveWithCallee(caller, method, currentDepth + 1, maxDepth, newPrefix, visited, result, context);
                } else {
                    result.append(prefix).append("   └─ `").append(callerKey).append("` [未找到]\n");
                }
            }
        }
    }

    /**
     * 从调用者方法体中提取调用被调用方法的代码行及其上下文
     *
     * @param caller 调用者方法
     * @param callee 被调用方法
     * @return 调用点代码（包含上下文）
     */
    private String extractCallSiteCode(MethodInfo caller, MethodInfo callee) {
        if (caller.getMethodBody() == null || caller.getMethodBody().isEmpty()) {
            return null;
        }

        String calleeMethodName = callee.getMethodName();
        String[] lines = caller.getMethodBody().split("\n");
        List<String> resultLines = new ArrayList<>();

        int contextLinesBefore = 2;  // 调用点前显示的行数
        int contextLinesAfter = 1;   // 调用点后显示的行数

        // 查找包含被调用方法名的行
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            // 检查是否包含方法调用（方法名后跟括号）
            if (containsMethodCall(line, calleeMethodName)) {
                // 提取上下文
                int start = Math.max(0, i - contextLinesBefore);
                int end = Math.min(lines.length - 1, i + contextLinesAfter);

                for (int j = start; j <= end; j++) {
                    String contextLine = lines[j].trim();
                    // 跳过空行和纯注释行
                    if (!contextLine.isEmpty() && !contextLine.startsWith("//") && !contextLine.startsWith("/*") && !contextLine.startsWith("*")) {
                        resultLines.add(contextLine);
                    }
                }
                break;  // 只取第一个调用点
            }
        }

        return resultLines.isEmpty() ? null : String.join("\n", resultLines);
    }

    /**
     * 检查代码行是否包含指定方法的调用
     * 支持多种调用场景：
     * 1. 对象方法调用: obj.methodName()
     * 2. 本类方法调用: methodName()
     * 3. 条件语句中: if (methodName())
     * 4. 赋值语句中: var = methodName()
     * 5. 方法参数中: func(methodName())
     * 6. return语句中: return methodName()
     */
    private boolean containsMethodCall(String line, String methodName) {
        // 模式1: 对象方法调用 .methodName(
        String dotPattern = "\\." + methodName + "\\s*\\(";
        if (line.matches(".*" + dotPattern + ".*")) {
            return true;
        }

        // 模式2-6: 使用更通用的正则表达式匹配
        // 匹配: 非字母数字字符 + methodName + 可选空格 + 左括号
        // 这样可以匹配: 空格、左括号、等号、逗号、return等后面的方法调用
        String generalPattern = "[^a-zA-Z0-9_]" + methodName + "\\s*\\(";
        if (line.matches(".*" + generalPattern + ".*")) {
            return true;
        }

        // 模式7: 行首调用 methodName(
        if (line.trim().startsWith(methodName + "(")) {
            return true;
        }

        return false;
    }

    /**
     * 完整链路追踪：同时展示正向和反向
     */
    private String traceFull(MethodInfo method, int maxDepth, CodeAnalyzeContextDTO context) {
        return traceBackward(method, maxDepth, context) +
                "\n" +
                traceForward(method, maxDepth, context);
    }

    /**
     * 查找方法
     */
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

            // 排除构造方法（构造方法的methodName等于className）
            // 除非用户明确要查找构造方法
            if (methodName.equals(className) && !targetMethodName.equals(className)) {
                continue;
            }

            if (methodName.equals(targetMethodName)) {
                if (targetClassName.isEmpty()) {
                    candidates.add(method);
                } else {
                    // 精确匹配优先：类名完全相等
                    if (className.equals(targetClassName)) {
                        candidates.add(0, method); // 插入到最前面
                    }
                    // 次优匹配：完整类名以目标类名结尾（如 com.example.Pipeline 匹配 pipeline）
                    else if (fullClassName.endsWith("." + targetClassName)) {
                        candidates.add(method);
                    }
                    // 模糊匹配：类名包含目标字符串（优先级最低）
                    else if (className.contains(targetClassName) || fullClassName.contains(targetClassName)) {
                        candidates.add(method);
                    }
                }
            }
        }

        return candidates.isEmpty() ? null : candidates.get(0);
    }

    /**
     * 根据方法标识查找方法信息
     */
    private MethodInfo findMethodByKey(String methodKey, CodeAnalyzeContextDTO context) {
        if (context.getMethodMap() != null) {
            return context.getMethodMap().get(methodKey);
        }
        return null;
    }


    /**
     * 获取方法所在层标签
     */
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

