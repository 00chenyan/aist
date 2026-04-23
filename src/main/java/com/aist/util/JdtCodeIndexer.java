package com.aist.util;

import com.aist.dto.MethodInfo;
import lombok.Setter;
import org.eclipse.jdt.core.dom.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 基于 Eclipse JDT 的 Java 代码解析器
 * 专注于代码解析，不包含向量化或存储逻辑
 */
public class JdtCodeIndexer {

    private final String projectRoot;

    /**
     * 类型解析器 - 用于推断变量的类型
     */
    static class TypeResolver {
        // 变量名 -> 简单类名（如：orderService -> OrderService）
        private final Map<String, String> variableToSimpleType = new HashMap<>();

        // 简单类名 -> 完整类名（如：OrderService -> com.example.service.OrderService）
        private final Map<String, String> simpleToFullType = new HashMap<>();

        // 通配符导入的包名列表（如：net.poweroak.saas.crm.modules.order.service）
        private final List<String> wildcardImports = new ArrayList<>();

        // 静态方法导入映射（方法名 -> 完整类名.方法名）
        // 例如：formatDate -> com.example.utils.DateUtils.formatDate
        private final Map<String, String> staticMethodImports = new HashMap<>();

        // 当前包名
        @Setter
        private String packageName = "";

        // 当前类的完整类名（用于解析本类方法调用）
        @Setter
        private String currentFullClassName = "";

        /**
         * 添加导入语句
         */
        public void addImport(String fullTypeName) {
            if (fullTypeName != null && fullTypeName.contains(".")) {
                String simpleName = fullTypeName.substring(fullTypeName.lastIndexOf('.') + 1);
                simpleToFullType.put(simpleName, fullTypeName);
            }
        }

        /**
         * 添加静态方法导入
         * 例如：import static com.example.utils.DateUtils.formatDate
         */
        public void addStaticMethodImport(String fullMethodPath) {
            if (fullMethodPath != null && fullMethodPath.contains(".")) {
                String methodName = fullMethodPath.substring(fullMethodPath.lastIndexOf('.') + 1);
                staticMethodImports.put(methodName, fullMethodPath);
            }
        }

        /**
         * 添加通配符导入
         */
        public void addWildcardImport(String packageName) {
            if (packageName != null && !packageName.isEmpty()) {
                wildcardImports.add(packageName);
            }
        }

        /**
         * 添加变量类型映射
         */
        public void addVariable(String variableName, String typeName) {
            if (variableName != null && typeName != null) {
                // 移除泛型参数，保留基础类名（如 List<OrderDTO> → List）
                String baseTypeName = typeName.replaceAll("<.*>", "").trim();
                variableToSimpleType.put(variableName, baseTypeName);
            }
        }

        /**
         * 解析类型名为完整类名
         * 例如：OrderDTO -> com.example.dto.OrderDTO
         */
        public String resolveType(String typeName) {
            if (typeName == null || typeName.isEmpty()) {
                return typeName;
            }

            // 移除泛型参数
            String baseTypeName = typeName.replaceAll("<.*>", "").trim();

            // 如果已经是完整类名（包含包名），直接返回
            if (baseTypeName.contains(".")) {
                return baseTypeName;
            }

            // 优先检查是否是JDK常用类
            String jdkType = resolveJdkType(baseTypeName);
            if (jdkType != null) {
                return jdkType;
            }

            // 尝试从导入映射中获取完整类名
            String fullType = simpleToFullType.get(baseTypeName);
            if (fullType != null) {
                return fullType;
            }

            // 尝试从通配符导入中查找
            String bestMatch = findBestWildcardMatch(baseTypeName);
            if (bestMatch != null) {
                return bestMatch;
            }

            // 可能是同包类
            if (!packageName.isEmpty()) {
                return packageName + "." + baseTypeName;
            }

            return baseTypeName;
        }

        /**
         * 解析JDK常用类型
         */
        private String resolveJdkType(String simpleTypeName) {
            // JDK集合类
            return switch (simpleTypeName) {
                case "List", "ArrayList", "LinkedList", "CopyOnWriteArrayList" -> "java.util." + simpleTypeName;
                case "Set", "HashSet", "LinkedHashSet", "TreeSet", "CopyOnWriteArraySet" ->
                        "java.util." + simpleTypeName;
                case "Map", "HashMap", "LinkedHashMap", "TreeMap", "ConcurrentHashMap", "Hashtable" ->
                        "java.util." + simpleTypeName;
                case "Queue", "Deque", "ArrayDeque", "PriorityQueue", "LinkedBlockingQueue" ->
                        "java.util." + simpleTypeName;
                case "Collection", "Collections", "Arrays", "Objects", "Optional" -> "java.util." + simpleTypeName;
                // JDK基础类
                case "String", "StringBuilder", "StringBuffer" -> "java.lang." + simpleTypeName;
                case "Integer", "Long", "Double", "Float", "Boolean", "Character", "Byte", "Short" ->
                        "java.lang." + simpleTypeName;
                case "BigDecimal", "BigInteger" -> "java.math." + simpleTypeName;
                case "Date", "Calendar" -> "java.util." + simpleTypeName;
                case "LocalDate", "LocalDateTime", "LocalTime", "ZonedDateTime", "Instant", "Duration" ->
                        "java.time." + simpleTypeName;
                case "Pattern", "Matcher" -> "java.util.regex." + simpleTypeName;
                case "File", "Path", "Paths", "Files" ->
                        simpleTypeName.equals("Path") || simpleTypeName.equals("Paths") || simpleTypeName.equals("Files") ? "java.nio.file." + simpleTypeName : "java.io." + simpleTypeName;
                case "InputStream", "OutputStream", "Reader", "Writer", "BufferedReader", "BufferedWriter" ->
                        "java.io." + simpleTypeName;
                case "Exception", "RuntimeException", "Throwable", "Error" -> "java.lang." + simpleTypeName;
                case "Thread", "Runnable", "Callable", "Future", "CompletableFuture" ->
                        simpleTypeName.equals("CompletableFuture") ? "java.util.concurrent." + simpleTypeName : "java.lang." + simpleTypeName;
                case "Stream", "Collectors" -> "java.util.stream." + simpleTypeName;
                case "Logger" -> "org.slf4j." + simpleTypeName;
                // JDK根基类
                case "Object" -> "java.lang.Object";
                case "Map.Entry" -> "java.util.Map.Entry";
                default -> null;
            };
        }

        /**
         * 解析完整的方法调用（变量名.方法名 -> 完整类名.方法名）
         */
        public String resolveMethodCall(String expression, String methodName) {
            if (expression == null || expression.isEmpty()) {
                // 没有调用者，可能是本类方法或静态导入
                // 优先检查是否是静态导入的方法
                String staticImport = staticMethodImports.get(methodName);
                if (staticImport != null) {
                    return staticImport;
                }

                // 其次返回当前类的完整方法调用
                if (!currentFullClassName.isEmpty()) {
                    return currentFullClassName + "." + methodName;
                }
                return methodName;
            }

            // 简化表达式（去掉复杂的链式调用）
            String caller = simplifyExpression(expression);

            // 特殊处理：Logger字段
            if ("log".equals(caller) || "logger".equals(caller)) {
                return "org.slf4j.Logger." + methodName;
            }

            // 特殊处理：this 关键字（本类内部方法调用，如 this.validate(order)）
            if ("this".equals(caller)) {
                if (!currentFullClassName.isEmpty()) {
                    return currentFullClassName + "." + methodName;
                }
            }

            // 尝试从变量映射中获取类型
            String simpleType = variableToSimpleType.get(caller);

            if (simpleType != null) {
                // 优先检查是否是JDK类型
                String jdkType = resolveJdkType(simpleType);
                if (jdkType != null) {
                    return jdkType + "." + methodName;
                }

                // 找到了变量的类型，尝试获取完整类名
                String fullType = simpleToFullType.get(simpleType);
                if (fullType != null) {
                    return fullType + "." + methodName;
                } else {
                    // 没有直接导入，尝试从通配符导入中查找
                    // 修复：优先选择包名中包含类型相关关键词的包
                    String bestMatch = findBestWildcardMatch(simpleType);
                    if (bestMatch != null) {
                        return bestMatch + "." + methodName;
                    }

                    // 没有导入信息，可能是同包类或内部类
                    if (!packageName.isEmpty()) {
                        return packageName + "." + simpleType + "." + methodName;
                    }
                    return simpleType + "." + methodName;
                }
            }

            // 变量映射未命中，尝试将 caller 作为类名解析（静态方法调用）
            // 例如：Collections.sort(list)、DateUtils.format(date)、BeanUtils.copyProperties(...)

            // 1. 检查 JDK 静态类（如 Collections、Arrays、Objects、Math 等）
            String jdkCallerType = resolveJdkType(caller);
            if (jdkCallerType != null) {
                return jdkCallerType + "." + methodName;
            }

            // 2. 检查精确导入的类名（如 import com.example.utils.DateUtils → DateUtils.format）
            String importedCallerType = simpleToFullType.get(caller);
            if (importedCallerType != null) {
                return importedCallerType + "." + methodName;
            }

            // 3. caller 首字母大写，符合类名命名约定，推断为同包静态类
            if (!caller.isEmpty() && Character.isUpperCase(caller.charAt(0)) && !packageName.isEmpty()) {
                return packageName + "." + caller + "." + methodName;
            }

            // 无法推断，返回原始格式
            return caller + "." + methodName;
        }

        /**
         * 从通配符导入中找到最佳匹配的包名
         * 优先级：
         * 1. 如果类型名以 "Service" 结尾，优先选择包含 ".service." 的包（而不是 ".service.impl."）
         * 2. 如果类型名以 "Dao" 结尾，优先选择包含 ".dao." 的包
         * 3. 如果类型名以 "Controller" 结尾，优先选择包含 ".controller." 的包
         * 4. 否则返回第一个匹配的包
         */
        private String findBestWildcardMatch(String simpleType) {
            if (wildcardImports.isEmpty()) {
                return null;
            }

            // 定义类型后缀和对应的包名模式
            Map<String, String> typeSuffixToPackagePattern = new HashMap<>();
            typeSuffixToPackagePattern.put("Service", ".service.");
            typeSuffixToPackagePattern.put("Dao", ".dao.");
            typeSuffixToPackagePattern.put("Controller", ".controller.");
            typeSuffixToPackagePattern.put("Repository", ".repository.");
            typeSuffixToPackagePattern.put("Mapper", ".mapper.");

            // 检查类型名的后缀
            for (Map.Entry<String, String> entry : typeSuffixToPackagePattern.entrySet()) {
                String suffix = entry.getKey();
                String packagePattern = entry.getValue();

                if (simpleType.endsWith(suffix)) {
                    // 优先选择包含对应模式的包，但排除 impl 包
                    for (String wildcardPackage : wildcardImports) {
                        if (wildcardPackage.contains(packagePattern) && !wildcardPackage.contains(".impl.")) {
                            return wildcardPackage + "." + simpleType;
                        }
                    }
                    // 如果没有找到非 impl 包，再尝试 impl 包
                    for (String wildcardPackage : wildcardImports) {
                        if (wildcardPackage.contains(packagePattern)) {
                            return wildcardPackage + "." + simpleType;
                        }
                    }
                }
            }

            // 没有匹配的后缀规则，不进行盲目猜测，返回 null
            // 调用方将继续尝试同包类推断（更保守但更准确）
            return null;
        }

        /**
         * 简化表达式（提取最后一个标识符）
         */
        private String simplifyExpression(String expression) {
            // 去掉方法调用：getUserService() -> getUserService
            expression = expression.replaceAll("\\(.*?\\)", "");

            // 提取最后一个点之后的部分
            if (expression.contains(".")) {
                String[] parts = expression.split("\\.");
                return parts[parts.length - 1];
            }

            return expression;
        }
    }

    public JdtCodeIndexer(String projectRoot) {
        this.projectRoot = projectRoot;
    }

    /**
     * 解析整个项目，返回所有方法信息
     */
    public List<MethodInfo> parseProject() {
        long startTime = System.currentTimeMillis();
        System.out.println("\n========== 开始解析项目 ==========");
        System.out.println("项目路径: " + projectRoot);

        // 获取所有 Java 文件
        List<File> javaFiles = findJavaFiles(new File(projectRoot));
        System.out.println("找到 " + javaFiles.size() + " 个 Java 文件");

        // 解析所有 Java 文件
        List<MethodInfo> allMethods = new ArrayList<>();
        int successCount = 0;
        int failCount = 0;

        for (File javaFile : javaFiles) {
            try {
                List<MethodInfo> methods = extractJavaMethods(javaFile);
                allMethods.addAll(methods);
                successCount++;
            } catch (Exception e) {
                System.err.println("  ✗ 文件解析失败: " + javaFile.getPath() + " - " + e.getMessage());
                failCount++;
            }
        }

        long totalTime = (System.currentTimeMillis() - startTime) / 1000;
        System.out.println("\n========== 解析完成 ==========");
        System.out.println("成功: " + successCount + " 个文件");
        System.out.println("失败: " + failCount + " 个文件");
        System.out.println("提取方法: " + allMethods.size() + " 个");
        System.out.println("总耗时: " + totalTime + " 秒\n");

        return allMethods;
    }

    /**
     * 使用 Eclipse JDT 提取 Java 文件中的方法信息
     */
    private List<MethodInfo> extractJavaMethods(File javaFile) {
        List<MethodInfo> methods = new ArrayList<>();

        try {
            // 读取文件内容
            String source = new String(Files.readAllBytes(javaFile.toPath()));

            // 创建 AST 解析器
            ASTParser parser = ASTParser.newParser(AST.JLS17);
            parser.setSource(source.toCharArray());
            parser.setKind(ASTParser.K_COMPILATION_UNIT);
            parser.setResolveBindings(false);  // 不解析绑定，提高性能

            // 解析
            CompilationUnit cu = (CompilationUnit) parser.createAST(null);

            // 获取包名
            String packageName = "";
            if (cu.getPackage() != null) {
                packageName = cu.getPackage().getName().getFullyQualifiedName();
            }

            // 创建类型解析器
            TypeResolver typeResolver = new TypeResolver();
            typeResolver.setPackageName(packageName);

            // 第一遍：收集导入语句
            List<?> imports = cu.imports();
            for (Object importObj : imports) {
                if (importObj instanceof ImportDeclaration importDecl) {
                    String importName = importDecl.getName().getFullyQualifiedName();

                    if (importDecl.isStatic()) {
                        // 静态导入：import static com.example.Utils.formatDate
                        if (!importDecl.isOnDemand()) {
                            typeResolver.addStaticMethodImport(importName);
                        }
                        // 静态通配符导入暂不处理（import static com.example.Utils.*）
                    } else if (!importDecl.isOnDemand()) {  // 不是 import xxx.*
                        typeResolver.addImport(importName);
                    } else {  // 是通配符导入 import xxx.*
                        typeResolver.addWildcardImport(importName);
                    }
                }
            }

            // 使用 Visitor 模式提取方法
            final String finalPackageName = packageName;
            final TypeResolver finalTypeResolver = typeResolver;

            cu.accept(new ASTVisitor() {
                private String currentClassName = "";
                private String currentFullClassName = "";
                private String currentClassRequestMapping = "";  // 类级别的 @RequestMapping 路径
                private TypeResolver currentTypeResolver = new TypeResolver();

                @Override
                public boolean visit(TypeDeclaration node) {
                    // 检查是否是内部类
                    boolean isInnerClass = node.getParent() instanceof TypeDeclaration;

                    if (isInnerClass) {
                        // 内部类：将其添加到类型映射中
                        String innerClassName = node.getName().getIdentifier();
                        String fullInnerClassName = currentFullClassName + "." + innerClassName;
                        currentTypeResolver.simpleToFullType.put(innerClassName, fullInnerClassName);
                        // 内部类不需要单独处理方法，停止遍历其子节点以防止方法被归属到外部类
                        return false;
                    }

                    // 外部类（顶层类）
                    currentClassName = node.getName().getIdentifier();
                    currentFullClassName = finalPackageName.isEmpty()
                            ? currentClassName
                            : finalPackageName + "." + currentClassName;

                    // 提取类级别的 @RequestMapping 注解
                    currentClassRequestMapping = extractClassRequestMapping(node);

                    // 为当前类创建新的类型解析器（继承导入信息）
                    currentTypeResolver = new TypeResolver();
                    currentTypeResolver.setPackageName(finalPackageName);

                    // 复制导入信息
                    currentTypeResolver.simpleToFullType.putAll(finalTypeResolver.simpleToFullType);

                    // 复制通配符导入信息
                    currentTypeResolver.wildcardImports.addAll(finalTypeResolver.wildcardImports);

                    // 复制静态方法导入信息
                    currentTypeResolver.staticMethodImports.putAll(finalTypeResolver.staticMethodImports);

                    // 收集内部类信息
                    for (Object typeObj : node.getTypes()) {
                        if (typeObj instanceof TypeDeclaration innerType) {
                            String innerClassName = innerType.getName().getIdentifier();
                            String fullInnerClassName = currentFullClassName + "." + innerClassName;
                            currentTypeResolver.simpleToFullType.put(innerClassName, fullInnerClassName);
                        }
                    }

                    // 收集类的字段类型
                    for (Object fieldObj : node.getFields()) {
                        if (fieldObj instanceof FieldDeclaration field) {
                            String fieldType = field.getType().toString();

                            for (Object fragmentObj : field.fragments()) {
                                if (fragmentObj instanceof VariableDeclarationFragment fragment) {
                                    String fieldName = fragment.getName().getIdentifier();
                                    currentTypeResolver.addVariable(fieldName, fieldType);
                                }
                            }
                        }
                    }

                    return super.visit(node);
                }

                @Override
                public boolean visit(EnumDeclaration node) {
                    boolean isInnerEnum = node.getParent() instanceof TypeDeclaration;
                    if (isInnerEnum) {
                        // 内部枚举：仅添加到类型映射中，停止遍历其子节点以防止方法被归属到外部类
                        String innerEnumName = node.getName().getIdentifier();
                        String fullInnerEnumName = currentFullClassName + "." + innerEnumName;
                        currentTypeResolver.simpleToFullType.put(innerEnumName, fullInnerEnumName);
                        return false;
                    }

                    // 顶层枚举类
                    currentClassName = node.getName().getIdentifier();
                    currentFullClassName = finalPackageName.isEmpty()
                            ? currentClassName
                            : finalPackageName + "." + currentClassName;

                    // 枚举类通常没有 @RequestMapping 注解
                    currentClassRequestMapping = "";

                    // 为当前枚举类创建新的类型解析器（继承导入信息）
                    currentTypeResolver = new TypeResolver();
                    currentTypeResolver.setPackageName(finalPackageName);
                    currentTypeResolver.simpleToFullType.putAll(finalTypeResolver.simpleToFullType);
                    currentTypeResolver.wildcardImports.addAll(finalTypeResolver.wildcardImports);
                    currentTypeResolver.staticMethodImports.putAll(finalTypeResolver.staticMethodImports);

                    // 收集枚举类的字段类型
                    for (Object bodyDecl : node.bodyDeclarations()) {
                        if (bodyDecl instanceof FieldDeclaration field) {
                            String fieldType = field.getType().toString();
                            for (Object fragmentObj : field.fragments()) {
                                if (fragmentObj instanceof VariableDeclarationFragment fragment) {
                                    String fieldName = fragment.getName().getIdentifier();
                                    currentTypeResolver.addVariable(fieldName, fieldType);
                                }
                            }
                        }
                    }

                    return super.visit(node);
                }

                @Override
                public boolean visit(MethodDeclaration node) {
                    try {
                        // 为当前方法创建类型解析器副本
                        TypeResolver methodTypeResolver = new TypeResolver();
                        methodTypeResolver.setPackageName(finalPackageName);
                        methodTypeResolver.setCurrentFullClassName(currentFullClassName);

                        // 复制类级别的类型信息
                        methodTypeResolver.variableToSimpleType.putAll(currentTypeResolver.variableToSimpleType);
                        methodTypeResolver.simpleToFullType.putAll(currentTypeResolver.simpleToFullType);

                        // 复制通配符导入信息
                        methodTypeResolver.wildcardImports.addAll(currentTypeResolver.wildcardImports);

                        // 复制静态方法导入信息
                        methodTypeResolver.staticMethodImports.putAll(currentTypeResolver.staticMethodImports);

                        // 收集方法参数类型
                        List<?> parameters = node.parameters();
                        for (Object param : parameters) {
                            if (param instanceof SingleVariableDeclaration svd) {
                                String paramName = svd.getName().getIdentifier();
                                String paramType = svd.getType().toString();
                                methodTypeResolver.addVariable(paramName, paramType);
                            }
                        }

                        // 收集方法内的局部变量类型
                        node.accept(new ASTVisitor() {
                            @Override
                            public boolean visit(VariableDeclarationStatement varDecl) {
                                String varType = varDecl.getType().toString();
                                for (Object fragmentObj : varDecl.fragments()) {
                                    if (fragmentObj instanceof VariableDeclarationFragment fragment) {
                                        String varName = fragment.getName().getIdentifier();
                                        methodTypeResolver.addVariable(varName, varType);
                                    }
                                }
                                return super.visit(varDecl);
                            }

                            @Override
                            public boolean visit(EnhancedForStatement forStmt) {
                                // 捕获增强 for 循环的迭代变量（如：for (Order o : orders)）
                                SingleVariableDeclaration param = forStmt.getParameter();
                                String varType = param.getType().toString();
                                String varName = param.getName().getIdentifier();
                                methodTypeResolver.addVariable(varName, varType);
                                return super.visit(forStmt);
                            }

                            @Override
                            public boolean visit(PatternInstanceofExpression node) {
                                // 捕获 instanceof 模式匹配绑定变量（如：obj instanceof List<?> list）
                                SingleVariableDeclaration svd = node.getRightOperand();
                                String varType = svd.getType().toString();
                                String varName = svd.getName().getIdentifier();
                                methodTypeResolver.addVariable(varName, varType);
                                return super.visit(node);
                            }
                        });

                        MethodInfo info = new MethodInfo();
                        info.setFilePath(getRelativePath(javaFile));
                        info.setPackageName(finalPackageName);
                        info.setClassName(currentClassName);
                        info.setFullClassName(currentFullClassName);
                        info.setMethodName(node.getName().getIdentifier());

                        // 构建方法签名
                        StringBuilder signature = new StringBuilder();
                        signature.append(node.getName().getIdentifier()).append("(");
                        for (int i = 0; i < parameters.size(); i++) {
                            if (i > 0) signature.append(", ");
                            Object param = parameters.get(i);
                            if (param instanceof SingleVariableDeclaration svd) {
                                signature.append(svd.getType().toString());
                            }
                        }
                        signature.append(")");
                        info.setMethodSignature(signature.toString());

                        // 获取行号
                        info.setStartLine(cu.getLineNumber(node.getStartPosition()));
                        info.setEndLine(cu.getLineNumber(node.getStartPosition() + node.getLength()));

                        // 获取方法体
                        info.setMethodBody(node.toString());

                        // 获取注释
                        Javadoc javadoc = node.getJavadoc();
                        if (javadoc != null) {
                            info.setJavadoc(javadoc.toString());
                        } else {
                            info.setJavadoc("");
                        }

                        // 提取方法调用关系（使用类型解析器）
                        info.setCalledMethods(extractMethodCalls(node, methodTypeResolver));

                        // 提取接口注解信息（传入类级别的 @RequestMapping 路径）
                        extractApiAnnotations(node, info, currentClassRequestMapping);

                        methods.add(info);
                    } catch (Exception e) {
                        System.err.println("  ⚠ 方法解析失败: " + currentClassName + "." + node.getName().getIdentifier());
                    }
                    return super.visit(node);
                }
            });

        } catch (Exception e) {
            System.err.println("  ✗ 文件解析失败: " + javaFile.getPath() + " - " + e.getMessage());
        }

        return methods;
    }

    /**
     * 提取方法中调用的其他方法（使用类型解析器）
     */
    private List<String> extractMethodCalls(MethodDeclaration methodNode, TypeResolver typeResolver) {
        List<String> calledMethods = new ArrayList<>();

        // 使用 Visitor 模式遍历方法体中的所有方法调用
        methodNode.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodInvocation node) {
                try {
                    String methodName = node.getName().getIdentifier();
                    String expression = node.getExpression() != null ? node.getExpression().toString() : "";

                    // 使用类型解析器解析完整的方法调用
                    String resolvedCall = typeResolver.resolveMethodCall(expression, methodName);

                    // 添加到列表（去重）
                    if (!calledMethods.contains(resolvedCall)) {
                        calledMethods.add(resolvedCall);
                    }
                } catch (Exception e) {
                    // 忽略解析错误
                }
                return super.visit(node);
            }

            @Override
            public boolean visit(SuperMethodInvocation node) {
                try {
                    String methodCall = "super." + node.getName().getIdentifier();
                    if (!calledMethods.contains(methodCall)) {
                        calledMethods.add(methodCall);
                    }
                } catch (Exception e) {
                    // 忽略解析错误
                }
                return super.visit(node);
            }

            @Override
            public boolean visit(org.eclipse.jdt.core.dom.ClassInstanceCreation node) {
                try {
                    // 获取构造的类型
                    org.eclipse.jdt.core.dom.Type type = node.getType();
                    String typeName = type.toString();

                    // 过滤JDK标准库类
                    if (isJdkClass(typeName)) {
                        return super.visit(node);
                    }

                    // 解析完整类名
                    String fullClassName = typeResolver.resolveType(typeName);
                    String constructorCall = "new:" + fullClassName;

                    // 添加到列表（去重）
                    if (!calledMethods.contains(constructorCall)) {
                        calledMethods.add(constructorCall);
                    }
                } catch (Exception e) {
                    // 忽略解析错误
                }
                return super.visit(node);
            }
        });

        return calledMethods;
    }

    /**
     * 判断是否是JDK标准库类
     */
    private boolean isJdkClass(String className) {
        // 移除泛型参数
        String baseClassName = className.replaceAll("<.*>", "").trim();

        return baseClassName.startsWith("java.") ||
                baseClassName.startsWith("javax.") ||
                baseClassName.equals("String") ||
                baseClassName.equals("Integer") ||
                baseClassName.equals("Long") ||
                baseClassName.equals("Double") ||
                baseClassName.equals("Float") ||
                baseClassName.equals("Boolean") ||
                baseClassName.equals("Character") ||
                baseClassName.equals("Byte") ||
                baseClassName.equals("Short") ||
                baseClassName.equals("BigDecimal") ||
                baseClassName.equals("BigInteger") ||
                baseClassName.equals("Date") ||
                baseClassName.equals("LocalDate") ||
                baseClassName.equals("LocalDateTime") ||
                baseClassName.equals("LocalTime") ||
                baseClassName.equals("ArrayList") ||
                baseClassName.equals("LinkedList") ||
                baseClassName.equals("HashMap") ||
                baseClassName.equals("LinkedHashMap") ||
                baseClassName.equals("TreeMap") ||
                baseClassName.equals("HashSet") ||
                baseClassName.equals("LinkedHashSet") ||
                baseClassName.equals("TreeSet") ||
                baseClassName.equals("Vector") ||
                baseClassName.equals("Stack") ||
                baseClassName.equals("Hashtable") ||
                baseClassName.equals("Properties") ||
                baseClassName.equals("StringBuilder") ||
                baseClassName.equals("StringBuffer");
    }


    /**
     * 提取类级别的 @RequestMapping 路径
     */
    private String extractClassRequestMapping(TypeDeclaration typeNode) {
        try {
            List<?> modifiers = typeNode.modifiers();
            for (Object modifier : modifiers) {
                String annotationStr = null;
                String annotationName = null;

                if (modifier instanceof Annotation annotation) {
                    annotationName = annotation.getTypeName().toString();
                    annotationStr = annotation.toString();
                }

                if (annotationName != null && annotationName.contains("RequestMapping")) {
                    // 提取路径
                    return extractPathFromAnnotation(annotationStr);
                }
            }
        } catch (Exception e) {
            // 忽略错误
        }
        return "";
    }

    /**
     * 提取方法上的 API 注解信息（PostMapping、GetMapping、ApiOperation 等）
     * 并拼接类级别的 @RequestMapping 路径
     */
    private void extractApiAnnotations(MethodDeclaration methodNode, MethodInfo info, String classRequestMapping) {
        try {
            List<?> modifiers = methodNode.modifiers();
            String methodMapping = "";
            String apiOperation = "";
            String httpMethod = "";  // GET, POST, PUT, DELETE

            for (Object modifier : modifiers) {
                String annotationStr = null;
                String annotationName = null;

                if (modifier instanceof Annotation annotation) {
                    annotationName = annotation.getTypeName().toString();
                    annotationStr = annotation.toString();
                }

                if (annotationName != null) {
                    // 提取 PostMapping 或 GetMapping 等映射注解
                    if (annotationName.contains("PostMapping")) {
                        httpMethod = "POST";
                        methodMapping = extractPathFromAnnotation(annotationStr);
                    } else if (annotationName.contains("GetMapping")) {
                        httpMethod = "GET";
                        methodMapping = extractPathFromAnnotation(annotationStr);
                    } else if (annotationName.contains("PutMapping")) {
                        httpMethod = "PUT";
                        methodMapping = extractPathFromAnnotation(annotationStr);
                    } else if (annotationName.contains("DeleteMapping")) {
                        httpMethod = "DELETE";
                        methodMapping = extractPathFromAnnotation(annotationStr);
                    } else if (annotationName.contains("RequestMapping")) {
                        methodMapping = extractPathFromAnnotation(annotationStr);
                        // RequestMapping 可能包含 method 属性，这里简化处理
                        httpMethod = "REQUEST";
                    }

                    // 提取 ApiOperation 注解
                    if (annotationName.contains("ApiOperation")) {
                        apiOperation = annotationStr;
                    }
                }
            }

            // 拼接完整的 API 路径
            String fullPath = buildFullApiPath(classRequestMapping, methodMapping, httpMethod);

            // 设置提取的注解信息
            info.setApiMapping(fullPath.isEmpty() ? "" : fullPath);
            info.setApiOperation(apiOperation.isEmpty() ? "" : apiOperation);

        } catch (Exception e) {
            // 如果提取失败，设置为空字符串
            info.setApiMapping("");
            info.setApiOperation("");
        }
    }

    /**
     * 从注解字符串中提取路径
     * 例如: @PostMapping("/start") -> /start
     */
    private String extractPathFromAnnotation(String annotationStr) {
        if (annotationStr == null || annotationStr.isEmpty()) {
            return "";
        }

        // 匹配 @XxxMapping("/path") 或 @XxxMapping(value = "/path")
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "@\\w+Mapping\\s*\\(\\s*(?:value\\s*=\\s*)?(?:\\{\\s*)?[\"']([^\"']+)[\"']"
        );
        java.util.regex.Matcher matcher = pattern.matcher(annotationStr);

        if (matcher.find()) {
            return matcher.group(1);
        }

        return "";
    }

    /**
     * 构建完整的 API 路径
     * 例如: classPath="/api/analysis", methodPath="/start", httpMethod="POST"
     * -> "POST /api/analysis/start"
     */
    private String buildFullApiPath(String classPath, String methodPath, String httpMethod) {
        if (httpMethod.isEmpty()) {
            return "";
        }

        StringBuilder fullPath = new StringBuilder();
        fullPath.append(httpMethod).append(" ");

        // 拼接类路径
        if (!classPath.isEmpty()) {
            if (!classPath.startsWith("/")) {
                fullPath.append("/");
            }
            fullPath.append(classPath);
        }

        // 拼接方法路径
        if (!methodPath.isEmpty()) {
            if (!methodPath.startsWith("/") && !fullPath.toString().endsWith("/")) {
                fullPath.append("/");
            }
            fullPath.append(methodPath);
        }

        // 如果只有 HTTP 方法和类路径，没有方法路径
        // 例如: @PostMapping (无参数) -> 使用类路径
        if (fullPath.toString().equals(httpMethod + " ")) {
            return "";
        }

        return fullPath.toString();
    }

    /**
     * 查找所有 Java 文件
     */
    private List<File> findJavaFiles(File directory) {
        List<File> javaFiles = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(Paths.get(directory.getPath()))) {
            paths
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.toString().contains("target"))
                    .filter(p -> !p.toString().contains("build"))
                    .map(Path::toFile)
                    .forEach(javaFiles::add);
        } catch (IOException e) {
            System.err.println("查找 Java 文件失败: " + e.getMessage());
        }
        return javaFiles;
    }

    /**
     * 获取相对路径
     */
    private String getRelativePath(File file) {
        return Paths.get(projectRoot).relativize(file.toPath()).toString();
    }


    /**
     * 构建反向调用关系（calledBy）
     * 遍历所有方法的 calledMethods，建立反向映射，填充每个方法的 calledBy 字段
     * 支持接口到实现类的映射，解决 Controller 调用 Service 接口但需要追踪到实现类的问题
     *
     * @param methods 方法信息列表
     */
    public void buildCalledByRelations(List<MethodInfo> methods) {
        // 第一步：构建方法标识到 MethodInfo 的映射（用于快速查找）
        // key: 完整类名.方法名（如：com.example.service.OrderService.createOrder）
        // value: List<MethodInfo>，支持同名重载方法共存，避免后者覆盖前者
        Map<String, List<MethodInfo>> methodMap = new HashMap<>();
        for (MethodInfo method : methods) {
            String methodKey = method.getFullClassName() + "." + method.getMethodName();
            methodMap.computeIfAbsent(methodKey, k -> new ArrayList<>()).add(method);
            // 初始化 calledBy 列表
            method.setCalledBy(new ArrayList<>());
        }

        // 第二步：构建接口到实现类的映射关系
        // key: 接口方法（如：com.example.service.OrderService.createOrder）
        // value: 实现类方法列表（如：[com.example.service.impl.OrderServiceImpl.createOrder]）
        Map<String, List<String>> interfaceToImplMap = buildInterfaceToImplementationMap(methods);

        System.out.println("✓ 已识别 " + interfaceToImplMap.size() + " 个接口方法的实现关系");

        // 第三步：遍历所有方法，构建反向调用关系
        for (MethodInfo caller : methods) {
            if (caller.getCalledMethods() == null || caller.getCalledMethods().isEmpty()) {
                continue;
            }

            String callerKey = caller.getFullClassName() + "." + caller.getMethodName();

            for (String calledMethod : caller.getCalledMethods()) {
                // 尝试在 methodMap 中查找被调用的方法（重载方法均添加调用者）
                List<MethodInfo> callees = methodMap.get(calledMethod);
                if (callees != null) {
                    for (MethodInfo callee : callees) {
                        if (!callee.getCalledBy().contains(callerKey)) {
                            callee.getCalledBy().add(callerKey);
                        }
                    }

                    // 如果调用的是接口方法，同时也将调用关系添加到所有实现类方法中
                    List<String> implMethods = interfaceToImplMap.get(calledMethod);
                    if (implMethods != null && !implMethods.isEmpty()) {
                        for (String implMethodKey : implMethods) {
                            List<MethodInfo> implMethodList = methodMap.get(implMethodKey);
                            if (implMethodList != null) {
                                for (MethodInfo implMethod : implMethodList) {
                                    if (!implMethod.getCalledBy().contains(callerKey)) {
                                        implMethod.getCalledBy().add(callerKey);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 统计有调用者的方法数量
        long methodsWithCallers = methods.stream()
                .filter(m -> m.getCalledBy() != null && !m.getCalledBy().isEmpty())
                .count();
        System.out.println("✓ 已构建反向调用关系，共 " + methodsWithCallers + " 个方法有调用者信息");
    }

    /**
     * 构建接口到实现类的映射关系
     * 通过分析类名模式识别接口和实现类（如：XxxService -> XxxServiceImpl）
     *
     * @param methods 所有方法信息
     * @return 接口方法到实现类方法的映射
     */
    private Map<String, List<String>> buildInterfaceToImplementationMap(List<MethodInfo> methods) {
        Map<String, List<String>> interfaceToImplMap = new HashMap<>();

        // 第一步：按类名分组所有方法
        Map<String, List<MethodInfo>> classMethods = new HashMap<>();
        for (MethodInfo method : methods) {
            classMethods.computeIfAbsent(method.getFullClassName(), k -> new ArrayList<>()).add(method);
        }

        // 第二步：解析所有 Java 文件，识别接口和实现关系
        Map<String, List<String>> classToInterfaces = parseClassImplementsRelations();

        // 第三步：建立接口方法到实现类方法的映射
        for (Map.Entry<String, List<String>> entry : classToInterfaces.entrySet()) {
            String implClassName = entry.getKey();
            List<String> interfaces = entry.getValue();

            List<MethodInfo> implMethods = classMethods.get(implClassName);
            if (implMethods == null) {
                continue;
            }

            for (String interfaceName : interfaces) {
                List<MethodInfo> interfaceMethods = classMethods.get(interfaceName);
                if (interfaceMethods == null) {
                    continue;
                }

                // 建立方法级别的映射
                for (MethodInfo interfaceMethod : interfaceMethods) {
                    String interfaceMethodKey = interfaceMethod.getFullClassName() + "." + interfaceMethod.getMethodName();

                    // 在实现类中查找同名方法
                    for (MethodInfo implMethod : implMethods) {
                        if (implMethod.getMethodName().equals(interfaceMethod.getMethodName())) {
                            String implMethodKey = implMethod.getFullClassName() + "." + implMethod.getMethodName();
                            interfaceToImplMap.computeIfAbsent(interfaceMethodKey, k -> new ArrayList<>()).add(implMethodKey);
                        }
                    }
                }
            }
        }

        return interfaceToImplMap;
    }

    /**
     * 解析所有类的 implements 关系
     * 返回: 实现类完整类名 -> 接口完整类名列表
     */
    private Map<String, List<String>> parseClassImplementsRelations() {
        Map<String, List<String>> classToInterfaces = new HashMap<>();

        try (var stream = Files.walk(Paths.get(projectRoot))) {
            // 遍历所有 Java 文件
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.toString().contains("target"))
                    .filter(p -> !p.toString().contains("build"))
                    .forEach(javaFile -> {
                        try {
                            parseClassImplements(javaFile.toFile(), classToInterfaces);
                        } catch (Exception e) {
                            // 忽略解析错误
                        }
                    });
        } catch (IOException e) {
            System.err.println("遍历文件失败: " + e.getMessage());
        }

        return classToInterfaces;
    }

    /**
     * 解析单个文件的 implements 关系
     */
    private void parseClassImplements(File javaFile, Map<String, List<String>> classToInterfaces) {
        try {
            String source = new String(Files.readAllBytes(javaFile.toPath()));
            ASTParser parser = ASTParser.newParser(AST.JLS17);
            parser.setSource(source.toCharArray());
            parser.setKind(ASTParser.K_COMPILATION_UNIT);

            CompilationUnit cu = (CompilationUnit) parser.createAST(null);

            // 获取包名
            String packageName = "";
            if (cu.getPackage() != null) {
                packageName = cu.getPackage().getName().getFullyQualifiedName();
            }

            // 收集导入信息（用于解析接口的完整类名）
            Map<String, String> imports = new HashMap<>();
            for (Object importObj : cu.imports()) {
                if (importObj instanceof ImportDeclaration importDecl) {
                    if (!importDecl.isOnDemand()) {
                        String importName = importDecl.getName().getFullyQualifiedName();
                        String simpleName = importName.substring(importName.lastIndexOf('.') + 1);
                        imports.put(simpleName, importName);
                    }
                }
            }

            final String finalPackageName = packageName;
            final Map<String, String> finalImports = imports;

            // 遍历所有类型声明
            cu.accept(new ASTVisitor() {
                @Override
                public boolean visit(TypeDeclaration node) {
                    String className = node.getName().getIdentifier();
                    String fullClassName = finalPackageName.isEmpty() ? className : finalPackageName + "." + className;

                    // 获取实现的接口列表
                    List<?> superInterfaces = node.superInterfaceTypes();
                    if (superInterfaces != null && !superInterfaces.isEmpty()) {
                        List<String> interfaceNames = new ArrayList<>();

                        for (Object interfaceObj : superInterfaces) {
                            // 剥离泛型参数（如 OrderService<T> → OrderService）
                            String interfaceName = interfaceObj.toString().replaceAll("<.*>", "").trim();

                            // 解析接口的完整类名
                            String fullInterfaceName = resolveFullClassName(interfaceName, finalPackageName, finalImports);
                            interfaceNames.add(fullInterfaceName);
                        }

                        classToInterfaces.put(fullClassName, interfaceNames);
                    }

                    return super.visit(node);
                }
            });

        } catch (Exception e) {
            // 忽略解析错误
        }
    }

    /**
     * 解析完整类名
     */
    private String resolveFullClassName(String simpleName, String currentPackage, Map<String, String> imports) {
        // 如果已经是完整类名（包含点），直接返回
        if (simpleName.contains(".")) {
            return simpleName;
        }

        // 从导入中查找
        if (imports.containsKey(simpleName)) {
            return imports.get(simpleName);
        }

        // 假设在同一个包下
        if (!currentPackage.isEmpty()) {
            return currentPackage + "." + simpleName;
        }

        return simpleName;
    }

}
