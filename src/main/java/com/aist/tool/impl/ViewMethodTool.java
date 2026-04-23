package com.aist.tool.impl;

import com.aist.dto.MethodInfo;
import com.aist.dto.CodeAnalyzeContextDTO;
import com.aist.service.ProjectParseService;
import com.aist.tool.ToolRequest;
import com.aist.tool.ToolResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Tool to view full method implementations: body, Javadoc, and call relations.
 */
@Slf4j
@Component
public class ViewMethodTool extends AbstractTool {

    @Autowired
    private ProjectParseService projectParseService;

    @Override
    public String getName() {
        return "VIEW_METHOD";
    }

    @Override
    public String getDescription() {
        return "查看方法的完整实现代码和详细信息，支持查看整个类的结构和所有方法";
    }

    @Override
    public String getParameterDescription() {
        return "类名.方法名[:深度] 或 类名[:模式] 或 方法名[:深度]";
    }

    @Override
    public List<String> getExamples() {
        return List.of(
                "[TOOL_CALL:VIEW_METHOD:CodeAnalyzeController.analyzeStream]    # single method",
                "[TOOL_CALL:VIEW_METHOD:UserService.login:2]                    # method + direct callees (2 levels)",
                "[TOOL_CALL:VIEW_METHOD:OrderService.createOrder:4]             # deeper tree (4 levels)",
                "[TOOL_CALL:VIEW_METHOD:createOrder]                            # method name only",
                "[TOOL_CALL:VIEW_METHOD:OrderService]                           # whole class (default summary)",
                "[TOOL_CALL:VIEW_METHOD:OrderService:full]                      # full class code",
                "[TOOL_CALL:VIEW_METHOD:OrderService:summary]                   # class structure summary"
        );
    }

    @Override
    public String getUsageScenario() {
        return "查看方法的完整实现代码、注释、调用关系，支持深度追踪调用链";
    }

    @Override
    public String getCapabilities() {
        return """
                支持：查看完整方法体、JavaDoc、方法签名、调用关系、深度调用链追踪、整个类的结构
                特点：返回完整代码，支持模糊匹配，支持1-10层深度分析
                
                深度可选项：
                  - 深度1: 只返回指定方法本身
                  - 深度2: 返回方法+直接调用的方法
                  - 深度3: 返回方法+2层调用链(推荐用于业务分析)
                  - 深度4-10: 完整调用树(用于深度分析)
                
                模式可选项：
                  - summary: 查看类的结构摘要（接口、字段、方法签名列表）
                  - full: 查看类的完整代码（包含所有方法实现）
                
                注意：
                  - 方法名不完整时自动模糊匹配，自动过滤getter/setter/日志方法
                  - 支持仅传入类名查看整个类的结构
                """;
    }

    @Override
    public int getPriority() {
        return 5; // highest: preferred for symbol-style lookups
    }

    @Override
    protected String validateRequest(ToolRequest request) {
        if (!request.hasArguments()) {
            return "请指定方法标识（类名.方法名 或 方法名）";
        }
        return null;
    }

    @Override
    protected ToolResult doExecute(ToolRequest request, CodeAnalyzeContextDTO context) {
        String methodKey = request.getFirstArgument();
        log.info("VIEW_METHOD: {}", methodKey);

        try {
            projectParseService.ensureProjectParsed(context);
        } catch (Exception e) {
            return ToolResult.error(getName(), methodKey, "项目解析失败: " + e.getMessage());
        }

        // Optional second arg: depth (1–10) or class mode (summary/full)
        int depth = 1;
        String mode = null; // class view: summary/full
        String actualMethodKey = methodKey;

        List<String> args = request.getArguments();
        if (args.size() > 1) {
            String param = args.get(1).trim();
            if (param.equals("summary") || param.equals("full")) {
                mode = param;
                log.info("Class view mode: {}", mode);
            } else {
                try {
                    depth = Integer.parseInt(param);
                    depth = Math.max(1, Math.min(depth, 10));
                    log.info("Call depth: {}", depth);
                } catch (NumberFormatException e) {
                    log.warn("Invalid depth; ignoring: {}", param);
                }
            }
        }

        String searchKey = actualMethodKey.toLowerCase().trim();
        String targetClassName = "";
        String targetMethodName = searchKey;

        if (searchKey.contains(".")) {
            int lastDot = searchKey.lastIndexOf(".");
            targetClassName = searchKey.substring(0, lastDot);
            targetMethodName = searchKey.substring(lastDot + 1);
        } else {
            // No dot: may be a class name for class-wide view
            List<MethodInfo> classMethods = findMethodsByClassName(searchKey, context);
            if (!classMethods.isEmpty() && mode != null) {
                return viewClass(searchKey, classMethods, mode, context);
            }
            if (!classMethods.isEmpty() && !searchKey.contains(".")) {
                String firstClassName = classMethods.get(0).getClassName();
                boolean sameClass = classMethods.stream()
                        .allMatch(m -> m.getClassName().equals(firstClassName));
                if (sameClass) {
                    return viewClass(searchKey, classMethods, "summary", context);
                }
            }
        }

        List<MethodInfo> matchedMethods = new ArrayList<>();
        for (MethodInfo method : context.getAllMethods()) {
            String className = method.getClassName().toLowerCase();
            String fullClassName = method.getFullClassName().toLowerCase();
            String methodName = method.getMethodName().toLowerCase();

            // Skip constructors unless the user asked for the constructor by simple class name
            if (methodName.equals(className) && !targetMethodName.equals(className)) {
                continue;
            }

            if (methodName.equals(targetMethodName)) {
                if (targetClassName.isEmpty()) {
                    matchedMethods.add(method);
                } else {
                    if (className.equals(targetClassName)) {
                        matchedMethods.add(0, method);
                    } else if (fullClassName.endsWith("." + targetClassName)) {
                        matchedMethods.add(method);
                    } else if (className.contains(targetClassName) || fullClassName.contains(targetClassName)) {
                        matchedMethods.add(method);
                    }
                }
            }
        }

        if (matchedMethods.isEmpty()) {
            return ToolResult.notFound(getName(), methodKey,
                    "未找到方法: " + methodKey + "\n\n" +
                            "提示: 请使用正确的格式调用\n" +
                            "正确示例:\n" +
                            "  [TOOL_CALL:VIEW_METHOD:UserService.login]  # 类名.方法名\n" +
                            "  [TOOL_CALL:VIEW_METHOD:CodeAnalyzeController.analyzeStream]  # 完整类名.方法名\n" +
                            "  [TOOL_CALL:VIEW_METHOD:login]  # 只有方法名（会模糊匹配）\n\n" +
                            "错误示例:\n" +
                            "  [TOOL_CALL:VIEW_METHOD:UserService]  #  缺少方法名\n" +
                            "  [TOOL_CALL:VIEW_METHOD:com.example.UserService]  #  缺少方法名、不需要带包名\n" +
                            "  [TOOL_CALL:VIEW_METHOD:UserService.java]  #  不要包含文件扩展名");
        }

        if (matchedMethods.size() > 1) {
            StringBuilder result = new StringBuilder();
            result.append("找到 ").append(matchedMethods.size()).append(" 个匹配的方法，请指定更精确的类名:\n\n");
            for (MethodInfo m : matchedMethods) {
                result.append("- `").append(m.getFullClassName()).append(".")
                        .append(m.getMethodName()).append("`");
                if (m.getApiMapping() != null && !m.getApiMapping().isEmpty()) {
                    result.append(" - API: `").append(m.getApiMapping()).append("`");
                }
                result.append("\n");
            }
            return ToolResult.success(getName(), methodKey, result.toString());
        }

        MethodInfo method = matchedMethods.get(0);

        if (depth == 1) {
            return ToolResult.success(getName(), methodKey, formatMethodDetails(method, context));
        } else {
            return ToolResult.success(getName(), methodKey, formatMethodTree(method, depth, context));
        }
    }

    /**
     * Formats a single method’s detail view.
     */
    private String formatMethodDetails(MethodInfo method, CodeAnalyzeContextDTO context) {
        StringBuilder result = new StringBuilder();

        // Basic info
        result.append("## 方法详细信息\n\n");
        result.append("**完整类名**: `").append(method.getFullClassName()).append("`\n");
        result.append("**方法名**: `").append(method.getMethodName()).append("`\n");
        result.append("**方法签名**: `").append(method.getMethodSignature()).append("`\n");
        result.append("**文件位置**: `").append(method.getFilePath()).append("`\n");
        result.append("**行号**: ").append(method.getStartLine()).append("-")
                .append(method.getEndLine()).append("\n");

        if (method.getApiMapping() != null && !method.getApiMapping().isEmpty()) {
            result.append("**API 路径**: `").append(method.getApiMapping()).append("`\n");
        }
        if (method.getApiOperation() != null && !method.getApiOperation().isEmpty()) {
            result.append("**API 说明**: ").append(method.getApiOperation()).append("\n");
        }

        result.append("\n");

        if (method.getJavadoc() != null && !method.getJavadoc().isEmpty()) {
            result.append("### JavaDoc 注释\n\n");
            result.append("```\n").append(method.getJavadoc()).append("\n```\n\n");
        }

        if (method.getCalledMethods() != null && !method.getCalledMethods().isEmpty()) {
            result.append("### 该方法调用的其他方法 (").append(method.getCalledMethods().size()).append(" 个)\n\n");
            for (String calledMethod : method.getCalledMethods()) {
                result.append("- `").append(calledMethod).append("`");

                MethodInfo calledMethodInfo = findMethodByKey(calledMethod, context);
                if (calledMethodInfo != null) {
                    result.append(" → `").append(calledMethodInfo.getFilePath()).append("`");
                    if (calledMethodInfo.getApiMapping() != null && !calledMethodInfo.getApiMapping().isEmpty()) {
                        result.append(" [API: ").append(calledMethodInfo.getApiMapping()).append("]");
                    }
                }
                result.append("\n");
            }
            result.append("\n");
        }

        if (method.getCalledBy() != null && !method.getCalledBy().isEmpty()) {
            result.append("### 调用该方法的其他方法 (").append(method.getCalledBy().size()).append(" 个)\n\n");
            for (String callerKey : method.getCalledBy()) {
                result.append("- `").append(callerKey).append("`");

                MethodInfo callerInfo = findMethodByKey(callerKey, context);
                if (callerInfo != null) {
                    result.append(" → `").append(callerInfo.getFilePath()).append("`");
                    if (callerInfo.getApiMapping() != null && !callerInfo.getApiMapping().isEmpty()) {
                        result.append(" [API: ").append(callerInfo.getApiMapping()).append("]");
                    }
                }
                result.append("\n");
            }
            result.append("\n");
        }

        // Full method body
        result.append("### 完整方法实现\n\n");
        result.append("```java\n");
        result.append(method.getMethodBody());
        result.append("\n```\n");

        return result.toString();
    }

    /**
     * Looks up a method by its key in the context map.
     */
    private MethodInfo findMethodByKey(String methodKey, CodeAnalyzeContextDTO context) {
        if (context.getMethodMap() != null) {
            return context.getMethodMap().get(methodKey);
        }
        return null;
    }

    /**
     * Renders a BFS call tree up to {@code maxDepth}.
     */
    private String formatMethodTree(MethodInfo startMethod, int maxDepth, CodeAnalyzeContextDTO context) {
        StringBuilder result = new StringBuilder();

        result.append("## 方法调用树分析\n\n");
        result.append("**起始方法**: `").append(startMethod.getFullClassName())
                .append(".").append(startMethod.getMethodName()).append("`\n");
        result.append("**追踪深度**: ").append(maxDepth).append(" 层\n");

        // BFS layers
        Map<Integer, List<MethodInfo>> layerMap = new LinkedHashMap<>();
        Set<String> visited = new HashSet<>();
        Queue<MethodNode> queue = new LinkedList<>();

        queue.offer(new MethodNode(startMethod, 1));
        visited.add(getMethodKey(startMethod));

        int totalMethods = 0;

        while (!queue.isEmpty()) {
            MethodNode node = queue.poll();

            if (node.depth > maxDepth) {
                break;
            }

            layerMap.computeIfAbsent(node.depth, k -> new ArrayList<>()).add(node.method);
            totalMethods++;

            if (node.method.getCalledMethods() != null && node.depth < maxDepth) {
                for (String calledKey : node.method.getCalledMethods()) {
                    if (!visited.contains(calledKey)) {
                        MethodInfo calledMethod = findMethodByKey(calledKey, context);

                        if (calledMethod != null && shouldIncludeMethod(calledMethod)) {
                            visited.add(calledKey);
                            queue.offer(new MethodNode(calledMethod, node.depth + 1));
                        }
                    }
                }
            }
        }

        result.append("**总方法数**: ").append(totalMethods).append(" 个\n\n");
        result.append("---\n\n");

        for (Map.Entry<Integer, List<MethodInfo>> entry : layerMap.entrySet()) {
            int layer = entry.getKey();
            List<MethodInfo> methods = entry.getValue();

            result.append("### 第").append(layer).append("层");
            result.append(" (").append(methods.size()).append("个方法)\n\n");

            for (MethodInfo method : methods) {
                result.append(formatMethodInTree(method, layer, context));
                result.append("\n");
            }
        }

        result.append("---\n\n");
        result.append("### 调用关系总结\n\n");
        result.append("```\n");
        result.append(buildCallTreeSummary(layerMap));
        result.append("```\n");

        return result.toString();
    }

    /**
     * Formats one method node in the tree.
     */
    private String formatMethodInTree(MethodInfo method, int layer, CodeAnalyzeContextDTO context) {
        StringBuilder sb = new StringBuilder();

        sb.append("#### ").append(method.getMethodName()).append("\n\n");
        sb.append("- **完整路径**: `").append(method.getFullClassName()).append("`\n");
        sb.append("- **文件位置**: `").append(method.getFilePath()).append("`\n");
        sb.append("- **行号**: ").append(method.getStartLine()).append("-").append(method.getEndLine()).append("\n");

        if (method.getApiMapping() != null && !method.getApiMapping().isEmpty()) {
            sb.append("- **API**: `").append(method.getApiMapping()).append("`\n");
        }

        if (method.getCalledMethods() != null && !method.getCalledMethods().isEmpty()) {
            List<String> nextLayerCalls = new ArrayList<>();
            for (String calledKey : method.getCalledMethods()) {
                MethodInfo calledMethod = findMethodByKey(calledKey, context);
                if (calledMethod != null && shouldIncludeMethod(calledMethod)) {
                    nextLayerCalls.add(calledMethod.getMethodName());
                }
            }
            if (!nextLayerCalls.isEmpty()) {
                sb.append("- **调用**: ").append(String.join(", ", nextLayerCalls)).append("\n");
            }
        }

        sb.append("\n**完整代码**:\n");
        sb.append("```java\n");
        sb.append(method.getMethodBody());
        sb.append("\n```\n");

        return sb.toString();
    }

    /**
     * Plain-text indented summary of the call tree.
     */
    private String buildCallTreeSummary(Map<Integer, List<MethodInfo>> layerMap) {
        StringBuilder sb = new StringBuilder();

        for (Map.Entry<Integer, List<MethodInfo>> entry : layerMap.entrySet()) {
            int layer = entry.getKey();
            List<MethodInfo> methods = entry.getValue();

            String indent = "  ".repeat(layer - 1);

            for (MethodInfo method : methods) {
                sb.append(indent);
                if (layer > 1) {
                    sb.append("├── ");
                }
                sb.append(method.getFullClassName()).append(".").append(method.getMethodName());

                String layerLabel = getLayerLabel(method);
                if (layerLabel != null) {
                    sb.append(" [").append(layerLabel).append("]");
                }

                sb.append("\n");
            }
        }

        return sb.toString();
    }

    /**
     * Filters trivial or framework noise from the tree.
     */
    private boolean shouldIncludeMethod(MethodInfo method) {
        String methodName = method.getMethodName().toLowerCase();
        String className = method.getFullClassName();

        if (methodName.startsWith("get") || methodName.startsWith("set") ||
                methodName.startsWith("is")) {
            // Keep “fat” getters (more than 3 lines)
            String body = method.getMethodBody();
            if (body != null && body.split("\n").length <= 3) {
                return false;
            }
        }

        if (methodName.equals("log") || methodName.equals("debug") ||
                methodName.equals("info") || methodName.equals("warn") ||
                methodName.equals("error") || methodName.equals("trace")) {
            return false;
        }

        if (className.startsWith("java.") || className.startsWith("javax.") ||
                className.startsWith("sun.") || className.startsWith("jdk.")) {
            return false;
        }

        return !className.startsWith("org.springframework.") && !className.startsWith("org.apache.commons.");
    }

    /**
     * Short role label for display (Controller, Service, …).
     */
    private String getLayerLabel(MethodInfo method) {
        String className = method.getClassName();

        if (className.endsWith("Controller")) {
            return "Controller";
        } else if (className.endsWith("Service") || className.endsWith("ServiceImpl")) {
            return "Service";
        } else if (className.endsWith("Mapper") || className.endsWith("Repository")) {
            return "Mapper";
        } else if (className.endsWith("Util") || className.endsWith("Utils") ||
                className.endsWith("Helper")) {
            return "Util";
        }

        return null;
    }

    /**
     * Unique key: {@code FQN.methodName}.
     */
    private String getMethodKey(MethodInfo method) {
        return method.getFullClassName() + "." + method.getMethodName();
    }

    /**
     * Lists methods whose simple or suffix-matched FQN matches {@code className}.
     */
    private List<MethodInfo> findMethodsByClassName(String className, CodeAnalyzeContextDTO context) {
        String lowerClassName = className.toLowerCase();
        List<MethodInfo> exactMatches = new ArrayList<>();
        List<MethodInfo> suffixMatches = new ArrayList<>();

        for (MethodInfo method : context.getAllMethods()) {
            String methodClassName = method.getClassName().toLowerCase();
            String fullClassName = method.getFullClassName().toLowerCase();

            if (methodClassName.equals(lowerClassName)) {
                exactMatches.add(method);
            } else if (fullClassName.endsWith("." + lowerClassName)) {
                suffixMatches.add(method);
            }
        }

        if (!exactMatches.isEmpty()) return exactMatches;
        return suffixMatches;
    }

    /**
     * Renders a class-level view in {@code summary} or {@code full} mode.
     */
    private ToolResult viewClass(String className, List<MethodInfo> classMethods,
                                 String mode, CodeAnalyzeContextDTO context) {
        if (classMethods.isEmpty()) {
            return ToolResult.notFound(getName(), className, "未找到类: " + className);
        }

        MethodInfo firstMethod = classMethods.get(0);
        String fullClassName = firstMethod.getFullClassName();
        String filePath = firstMethod.getFilePath();

        StringBuilder result = new StringBuilder();
        result.append("## 类详细信息\n\n");
        result.append("**完整类名**: `").append(fullClassName).append("`\n");
        result.append("**文件路径**: `").append(filePath).append("`\n");
        result.append("**方法数量**: ").append(classMethods.size()).append(" 个\n\n");

        try {
            String fileContent = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get(context.getProjectPath(), filePath)));

            String classDefinition = extractClassDefinition(fileContent, firstMethod.getClassName());
            if (classDefinition != null) {
                result.append("### 类定义\n\n");
                result.append("```java\n").append(classDefinition).append("\n```\n\n");
            }
        } catch (Exception e) {
            log.warn("Cannot read file: {}", filePath);
        }

        if ("full".equals(mode)) {
            // Full mode: show full code for all methods
            result.append("### 所有方法实现\n\n");
            for (int i = 0; i < classMethods.size(); i++) {
                MethodInfo method = classMethods.get(i);
                result.append("#### ").append(i + 1).append(". ")
                        .append(method.getMethodName()).append("\n\n");
                result.append("**方法签名**: `").append(method.getMethodSignature()).append("`\n");
                result.append("**行号**: ").append(method.getStartLine())
                        .append("-").append(method.getEndLine()).append("\n\n");

                if (method.getJavadoc() != null && !method.getJavadoc().isEmpty()) {
                    result.append("**JavaDoc**:\n```\n")
                            .append(method.getJavadoc()).append("\n```\n\n");
                }

                result.append("**代码**:\n```java\n")
                        .append(method.getMethodBody()).append("\n```\n\n");
                result.append("---\n\n");
            }
        } else {
            // Summary mode: method signatures only
            result.append("### 方法列表\n\n");

            Map<String, List<MethodInfo>> methodsByType = new LinkedHashMap<>();
            methodsByType.put("API接口方法", new ArrayList<>());
            methodsByType.put("公共方法", new ArrayList<>());
            methodsByType.put("私有方法", new ArrayList<>());

            for (MethodInfo method : classMethods) {
                if (method.getApiMapping() != null && !method.getApiMapping().isEmpty()) {
                    methodsByType.get("API接口方法").add(method);
                } else if (method.getMethodSignature().contains("public")) {
                    methodsByType.get("公共方法").add(method);
                } else {
                    methodsByType.get("私有方法").add(method);
                }
            }

            for (Map.Entry<String, List<MethodInfo>> entry : methodsByType.entrySet()) {
                if (!entry.getValue().isEmpty()) {
                    result.append("#### ").append(entry.getKey())
                            .append(" (").append(entry.getValue().size()).append("个)\n\n");

                    for (MethodInfo method : entry.getValue()) {
                        result.append("- `").append(method.getMethodSignature()).append("`");
                        if (method.getApiMapping() != null && !method.getApiMapping().isEmpty()) {
                            result.append(" → API: `").append(method.getApiMapping()).append("`");
                        }
                        result.append("\n");

                        if (method.getJavadoc() != null && !method.getJavadoc().isEmpty()) {
                            String firstLine = method.getJavadoc().split("\n")[0].trim();
                            if (!firstLine.isEmpty()) {
                                result.append("  > ").append(firstLine).append("\n");
                            }
                        }
                    }
                    result.append("\n");
                }
            }

            result.append("\n **提示**: 使用 `[TOOL_CALL:VIEW_METHOD:")
                    .append(className).append(":full]` 查看所有方法的完整代码\n");
        }

        return ToolResult.success(getName(), className, result.toString());
    }

    /**
     * Extracts the opening declaration block (class/interface/enum) for display.
     */
    private String extractClassDefinition(String fileContent, String className) {
        String[] lines = fileContent.split("\n");
        StringBuilder classDefBuilder = new StringBuilder();
        boolean foundClass = false;
        int braceCount = 0;

        for (String line : lines) {
            String trimmed = line.trim();

            if (!foundClass && (trimmed.contains("class " + className) ||
                    trimmed.contains("interface " + className) ||
                    trimmed.contains("enum " + className))) {
                foundClass = true;
                classDefBuilder.append(line).append("\n");

                braceCount += countChar(line, '{') - countChar(line, '}');

                if (braceCount == 0 && line.contains("{")) {
                    break;
                }
                continue;
            }

            if (foundClass && braceCount == 0) {
                classDefBuilder.append(line).append("\n");
                braceCount += countChar(line, '{') - countChar(line, '}');

                if (line.contains("{")) {
                    break;
                }
            }
        }

        return foundClass ? classDefBuilder.toString().trim() : null;
    }

    private int countChar(String str, char ch) {
        return (int) str.chars().filter(c -> c == ch).count();
    }

    /** BFS queue node. */
    private static class MethodNode {
        MethodInfo method;
        int depth;

        MethodNode(MethodInfo method, int depth) {
            this.method = method;
            this.depth = depth;
        }
    }
}

