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
 * 查看完整方法实现工具
 * 返回方法的完整代码、注释、调用关系等详细信息
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
                "[TOOL_CALL:VIEW_METHOD:CodeAnalyzeController.analyzeStream]    # 查看单个方法",
                "[TOOL_CALL:VIEW_METHOD:UserService.login:2]                    # 查看方法+直接调用(2层)",
                "[TOOL_CALL:VIEW_METHOD:OrderService.createOrder:4]             # 深度分析(4层)",
                "[TOOL_CALL:VIEW_METHOD:createOrder]                            # 只指定方法名",
                "[TOOL_CALL:VIEW_METHOD:OrderService]                           # 查看整个类(默认summary模式)",
                "[TOOL_CALL:VIEW_METHOD:OrderService:full]                      # 查看整个类的完整代码",
                "[TOOL_CALL:VIEW_METHOD:OrderService:summary]                   # 查看类结构摘要"
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
        return 5; // 最高优先级，代码符号搜索首选
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
        log.info("查看方法实现: {}", methodKey);

        // 自动确保项目已解析
        try {
            projectParseService.ensureProjectParsed(context);
        } catch (Exception e) {
            return ToolResult.error(getName(), methodKey, "项目解析失败: " + e.getMessage());
        }

        // 解析参数（深度或模式）——第二个参数为可选的深度或模式
        int depth = 1; // 默认深度为1
        String mode = null; // 类查看模式: summary/full
        String actualMethodKey = methodKey;

        List<String> args = request.getArguments();
        if (args.size() > 1) {
            String param = args.get(1).trim();
            // 判断是深度参数还是模式参数
            if (param.equals("summary") || param.equals("full")) {
                mode = param;
                log.info("使用类查看模式: {}", mode);
            } else {
                try {
                    depth = Integer.parseInt(param);
                    depth = Math.max(1, Math.min(depth, 10)); // 限制在1-10之间
                    log.info("使用深度参数: {}", depth);
                } catch (NumberFormatException e) {
                    log.warn("参数格式错误，使用默认值: {}", param);
                }
            }
        }

        // 解析类名和方法名
        String searchKey = actualMethodKey.toLowerCase().trim();
        String targetClassName = "";
        String targetMethodName = searchKey;

        if (searchKey.contains(".")) {
            int lastDot = searchKey.lastIndexOf(".");
            targetClassName = searchKey.substring(0, lastDot);
            targetMethodName = searchKey.substring(lastDot + 1);
        } else {
            // 如果没有点号，可能是类名（用于查看整个类）
            // 先尝试作为类名查找
            List<MethodInfo> classMethods = findMethodsByClassName(searchKey, context);
            if (!classMethods.isEmpty() && mode != null) {
                // 确认是类查看模式
                return viewClass(searchKey, classMethods, mode, context);
            }
            // 否则尝试作为类名查看（默认summary模式）
            if (!classMethods.isEmpty() && !searchKey.contains(".")) {
                // 检查是否所有方法都属于同一个类
                String firstClassName = classMethods.get(0).getClassName();
                boolean sameClass = classMethods.stream()
                        .allMatch(m -> m.getClassName().equals(firstClassName));
                if (sameClass) {
                    return viewClass(searchKey, classMethods, "summary", context);
                }
            }
        }

        // 查找匹配的方法
        List<MethodInfo> matchedMethods = new ArrayList<>();
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
                    matchedMethods.add(method);
                } else {
                    // 精确匹配优先：类名完全相等
                    if (className.equals(targetClassName)) {
                        matchedMethods.add(0, method); // 插入到最前面
                    }
                    // 次优匹配：完整类名以目标类名结尾（如 com.example.Pipeline 匹配 pipeline）
                    else if (fullClassName.endsWith("." + targetClassName)) {
                        matchedMethods.add(method);
                    }
                    // 模糊匹配：类名包含目标字符串（优先级最低）
                    else if (className.contains(targetClassName) || fullClassName.contains(targetClassName)) {
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

        // 如果找到多个匹配，列出所有选项
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

        // 返回唯一匹配的方法详细信息
        MethodInfo method = matchedMethods.get(0);

        // 根据深度参数决定返回格式
        if (depth == 1) {
            // 原有逻辑：只返回单个方法
            return ToolResult.success(getName(), methodKey, formatMethodDetails(method, context));
        } else {
            // 新逻辑：返回多层调用树
            return ToolResult.success(getName(), methodKey, formatMethodTree(method, depth, context));
        }
    }

    /**
     * 格式化方法详细信息
     */
    private String formatMethodDetails(MethodInfo method, CodeAnalyzeContextDTO context) {
        StringBuilder result = new StringBuilder();

        // 基本信息
        result.append("## 方法详细信息\n\n");
        result.append("**完整类名**: `").append(method.getFullClassName()).append("`\n");
        result.append("**方法名**: `").append(method.getMethodName()).append("`\n");
        result.append("**方法签名**: `").append(method.getMethodSignature()).append("`\n");
        result.append("**文件位置**: `").append(method.getFilePath()).append("`\n");
        result.append("**行号**: ").append(method.getStartLine()).append("-")
                .append(method.getEndLine()).append("\n");

        // API 信息
        if (method.getApiMapping() != null && !method.getApiMapping().isEmpty()) {
            result.append("**API 路径**: `").append(method.getApiMapping()).append("`\n");
        }
        if (method.getApiOperation() != null && !method.getApiOperation().isEmpty()) {
            result.append("**API 说明**: ").append(method.getApiOperation()).append("\n");
        }

        result.append("\n");

        // JavaDoc 注释
        if (method.getJavadoc() != null && !method.getJavadoc().isEmpty()) {
            result.append("### JavaDoc 注释\n\n");
            result.append("```\n").append(method.getJavadoc()).append("\n```\n\n");
        }

        // 方法调用关系（该方法调用了哪些方法）
        if (method.getCalledMethods() != null && !method.getCalledMethods().isEmpty()) {
            result.append("### 该方法调用的其他方法 (").append(method.getCalledMethods().size()).append(" 个)\n\n");
            for (String calledMethod : method.getCalledMethods()) {
                result.append("- `").append(calledMethod).append("`");

                // 尝试获取被调用方法的详细信息
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

        // 反向调用关系（哪些方法调用了该方法）
        if (method.getCalledBy() != null && !method.getCalledBy().isEmpty()) {
            result.append("### 调用该方法的其他方法 (").append(method.getCalledBy().size()).append(" 个)\n\n");
            for (String callerKey : method.getCalledBy()) {
                result.append("- `").append(callerKey).append("`");

                // 尝试获取调用者的详细信息
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

        // 完整方法体
        result.append("### 完整方法实现\n\n");
        result.append("```java\n");
        result.append(method.getMethodBody());
        result.append("\n```\n");

        return result.toString();
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
     * 格式化方法调用树（支持深度参数）
     */
    private String formatMethodTree(MethodInfo startMethod, int maxDepth, CodeAnalyzeContextDTO context) {
        StringBuilder result = new StringBuilder();

        // 标题信息
        result.append("## 方法调用树分析\n\n");
        result.append("**起始方法**: `").append(startMethod.getFullClassName())
                .append(".").append(startMethod.getMethodName()).append("`\n");
        result.append("**追踪深度**: ").append(maxDepth).append(" 层\n");

        // 使用BFS构建调用树
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

            // 按层级分组
            layerMap.computeIfAbsent(node.depth, k -> new ArrayList<>()).add(node.method);
            totalMethods++;

            // 获取该方法调用的其他方法
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

        // 按层级输出
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

        // 添加调用关系总结
        result.append("---\n\n");
        result.append("### 调用关系总结\n\n");
        result.append("```\n");
        result.append(buildCallTreeSummary(layerMap));
        result.append("```\n");

        return result.toString();
    }

    /**
     * 格式化树中的单个方法
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

        // 显示该方法调用的下一层方法
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
     * 构建调用树摘要
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

                // 添加层级标签
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
     * 判断是否应该包含该方法
     */
    private boolean shouldIncludeMethod(MethodInfo method) {
        String methodName = method.getMethodName().toLowerCase();
        String className = method.getFullClassName();

        // 排除 getter/setter
        if (methodName.startsWith("get") || methodName.startsWith("set") ||
                methodName.startsWith("is")) {
            // 但保留业务getter（方法体超过3行）
            String body = method.getMethodBody();
            if (body != null && body.split("\n").length <= 3) {
                return false;
            }
        }

        // 排除日志方法
        if (methodName.equals("log") || methodName.equals("debug") ||
                methodName.equals("info") || methodName.equals("warn") ||
                methodName.equals("error") || methodName.equals("trace")) {
            return false;
        }

        // 排除 JDK 标准库
        if (className.startsWith("java.") || className.startsWith("javax.") ||
                className.startsWith("sun.") || className.startsWith("jdk.")) {
            return false;
        }

        // 排除常见框架的基础方法
        return !className.startsWith("org.springframework.") && !className.startsWith("org.apache.commons.");
    }

    /**
     * 获取方法的层级标签
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
     * 获取方法的唯一标识
     */
    private String getMethodKey(MethodInfo method) {
        return method.getFullClassName() + "." + method.getMethodName();
    }

    /**
     * 根据类名查找所有方法
     */
    private List<MethodInfo> findMethodsByClassName(String className, CodeAnalyzeContextDTO context) {
        String lowerClassName = className.toLowerCase();
        List<MethodInfo> exactMatches = new ArrayList<>();
        List<MethodInfo> suffixMatches = new ArrayList<>();

        for (MethodInfo method : context.getAllMethods()) {
            String methodClassName = method.getClassName().toLowerCase();
            String fullClassName = method.getFullClassName().toLowerCase();

            if (methodClassName.equals(lowerClassName)) {
                // 精确匹配：类名完全一致（如 UserService 匹配 UserService）
                exactMatches.add(method);
            } else if (fullClassName.endsWith("." + lowerClassName)) {
                // 后缀匹配：完整类名以 .className 结尾（如 com.example.UserService 匹配 userservice）
                suffixMatches.add(method);
            }
            // 不使用 contains() 避免 UserService 误匹配 UserServiceImpl 等同名前缀类
        }

        // 精确匹配优先；无精确匹配时返回后缀匹配结果
        if (!exactMatches.isEmpty()) return exactMatches;
        return suffixMatches;
    }

    /**
     * 查看整个类
     */
    private ToolResult viewClass(String className, List<MethodInfo> classMethods,
                                 String mode, CodeAnalyzeContextDTO context) {
        if (classMethods.isEmpty()) {
            return ToolResult.notFound(getName(), className, "未找到类: " + className);
        }

        // 获取类的基本信息（从第一个方法获取）
        MethodInfo firstMethod = classMethods.get(0);
        String fullClassName = firstMethod.getFullClassName();
        String filePath = firstMethod.getFilePath();

        StringBuilder result = new StringBuilder();
        result.append("## 类详细信息\n\n");
        result.append("**完整类名**: `").append(fullClassName).append("`\n");
        result.append("**文件路径**: `").append(filePath).append("`\n");
        result.append("**方法数量**: ").append(classMethods.size()).append(" 个\n\n");

        // 尝试读取文件获取类定义信息
        try {
            String fileContent = new String(java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get(context.getProjectPath(), filePath)));

            // 提取类定义行（包含implements/extends信息）
            String classDefinition = extractClassDefinition(fileContent, firstMethod.getClassName());
            if (classDefinition != null) {
                result.append("### 类定义\n\n");
                result.append("```java\n").append(classDefinition).append("\n```\n\n");
            }
        } catch (Exception e) {
            log.warn("无法读取文件: {}", filePath);
        }

        if ("full".equals(mode)) {
            // 完整模式：显示所有方法的完整代码
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
            // 摘要模式：只显示方法签名列表
            result.append("### 方法列表\n\n");

            // 按类型分组
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
     * 从文件内容中提取类定义
     */
    private String extractClassDefinition(String fileContent, String className) {
        String[] lines = fileContent.split("\n");
        StringBuilder classDefBuilder = new StringBuilder();
        boolean foundClass = false;
        int braceCount = 0;

        for (String line : lines) {
            String trimmed = line.trim();

            // 查找类定义行
            if (!foundClass && (trimmed.contains("class " + className) ||
                    trimmed.contains("interface " + className) ||
                    trimmed.contains("enum " + className))) {
                foundClass = true;
                classDefBuilder.append(line).append("\n");

                // 计算大括号
                braceCount += countChar(line, '{') - countChar(line, '}');

                // 如果类定义在一行内结束，直接返回
                if (braceCount == 0 && line.contains("{")) {
                    break;
                }
                continue;
            }

            // 继续收集类定义（处理多行定义）
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

    /**
     * 计算字符出现次数
     */
    private int countChar(String str, char ch) {
        return (int) str.chars().filter(c -> c == ch).count();
    }

    /**
     * 内部类：方法节点（用于BFS遍历）
     */
    private static class MethodNode {
        MethodInfo method;
        int depth;

        MethodNode(MethodInfo method, int depth) {
            this.method = method;
            this.depth = depth;
        }
    }
}

