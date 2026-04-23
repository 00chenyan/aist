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
 * Java code parser based on Eclipse JDT, focused on parsing only (no vectorization or persistence).
 */
public class JdtCodeIndexer {

    private final String projectRoot;

    /**
     * Resolves types for local variables, fields, and static imports.
     */
    static class TypeResolver {
        // variable name -> simple class name (e.g. orderService -> OrderService)
        private final Map<String, String> variableToSimpleType = new HashMap<>();

        // simple class name -> fully qualified name (e.g. OrderService -> com.example.service.OrderService)
        private final Map<String, String> simpleToFullType = new HashMap<>();

        // on-demand import package prefixes (e.g. net.example.app.modules.order.service)
        private final List<String> wildcardImports = new ArrayList<>();

        // static method imports: method name -> fully qualified class.method
        // e.g. formatDate -> com.example.utils.DateUtils.formatDate
        private final Map<String, String> staticMethodImports = new HashMap<>();

        // current package name
        @Setter
        private String packageName = "";

        // current class FQN (for this-class method calls)
        @Setter
        private String currentFullClassName = "";

        /**
         * Records a type import.
         */
        public void addImport(String fullTypeName) {
            if (fullTypeName != null && fullTypeName.contains(".")) {
                String simpleName = fullTypeName.substring(fullTypeName.lastIndexOf('.') + 1);
                simpleToFullType.put(simpleName, fullTypeName);
            }
        }

        /**
         * Records a static method import, e.g. {@code import static com.example.utils.DateUtils.formatDate}.
         */
        public void addStaticMethodImport(String fullMethodPath) {
            if (fullMethodPath != null && fullMethodPath.contains(".")) {
                String methodName = fullMethodPath.substring(fullMethodPath.lastIndexOf('.') + 1);
                staticMethodImports.put(methodName, fullMethodPath);
            }
        }

        /**
         * Records an on-demand (wildcard) import package prefix.
         */
        public void addWildcardImport(String packageName) {
            if (packageName != null && !packageName.isEmpty()) {
                wildcardImports.add(packageName);
            }
        }

        /**
         * Maps a variable name to its declared type.
         */
        public void addVariable(String variableName, String typeName) {
            if (variableName != null && typeName != null) {
                // Strip type arguments; keep the raw name (e.g. List<OrderDTO> -> List)
                String baseTypeName = typeName.replaceAll("<.*>", "").trim();
                variableToSimpleType.put(variableName, baseTypeName);
            }
        }

        /**
         * Resolves a type name to a fully qualified class name, e.g. OrderDTO -> com.example.dto.OrderDTO.
         */
        public String resolveType(String typeName) {
            if (typeName == null || typeName.isEmpty()) {
                return typeName;
            }

            // Strip type arguments
            String baseTypeName = typeName.replaceAll("<.*>", "").trim();

            // Already fully qualified
            if (baseTypeName.contains(".")) {
                return baseTypeName;
            }

            // Prefer common JDK types
            String jdkType = resolveJdkType(baseTypeName);
            if (jdkType != null) {
                return jdkType;
            }

            // Look up explicit import
            String fullType = simpleToFullType.get(baseTypeName);
            if (fullType != null) {
                return fullType;
            }

            // Try wildcard imports
            String bestMatch = findBestWildcardMatch(baseTypeName);
            if (bestMatch != null) {
                return bestMatch;
            }

            // Default: same package
            if (!packageName.isEmpty()) {
                return packageName + "." + baseTypeName;
            }

            return baseTypeName;
        }

        /**
         * Resolves common JDK types from simple names.
         */
        private String resolveJdkType(String simpleTypeName) {
            // java.util collections and related
            return switch (simpleTypeName) {
                case "List", "ArrayList", "LinkedList", "CopyOnWriteArrayList" -> "java.util." + simpleTypeName;
                case "Set", "HashSet", "LinkedHashSet", "TreeSet", "CopyOnWriteArraySet" ->
                        "java.util." + simpleTypeName;
                case "Map", "HashMap", "LinkedHashMap", "TreeMap", "ConcurrentHashMap", "Hashtable" ->
                        "java.util." + simpleTypeName;
                case "Queue", "Deque", "ArrayDeque", "PriorityQueue", "LinkedBlockingQueue" ->
                        "java.util." + simpleTypeName;
                case "Collection", "Collections", "Arrays", "Objects", "Optional" -> "java.util." + simpleTypeName;
                // java.lang and basics
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
                // java.lang root types
                case "Object" -> "java.lang.Object";
                case "Map.Entry" -> "java.util.Map.Entry";
                default -> null;
            };
        }

        /**
         * Resolves a method call: receiver.name -> fully qualified class.name.
         */
        public String resolveMethodCall(String expression, String methodName) {
            if (expression == null || expression.isEmpty()) {
                // No explicit receiver: same class or static import
                // Prefer static import
                String staticImport = staticMethodImports.get(methodName);
                if (staticImport != null) {
                    return staticImport;
                }

                // Otherwise current class
                if (!currentFullClassName.isEmpty()) {
                    return currentFullClassName + "." + methodName;
                }
                return methodName;
            }

            // Simplify (strip nested calls from the expression)
            String caller = simplifyExpression(expression);

            // Logger field
            if ("log".equals(caller) || "logger".equals(caller)) {
                return "org.slf4j.Logger." + methodName;
            }

            // this. calls (e.g. this.validate(order))
            if ("this".equals(caller)) {
                if (!currentFullClassName.isEmpty()) {
                    return currentFullClassName + "." + methodName;
                }
            }

            // Resolve from variable name -> type
            String simpleType = variableToSimpleType.get(caller);

            if (simpleType != null) {
                // Prefer JDK
                String jdkType = resolveJdkType(simpleType);
                if (jdkType != null) {
                    return jdkType + "." + methodName;
                }

                // Explicit import for the variable's type
                String fullType = simpleToFullType.get(simpleType);
                if (fullType != null) {
                    return fullType + "." + methodName;
                } else {
                    // No direct import: try wildcard; prefer package patterns matching the type
                    String bestMatch = findBestWildcardMatch(simpleType);
                    if (bestMatch != null) {
                        return bestMatch + "." + methodName;
                    }

                    // Same package or simple type
                    if (!packageName.isEmpty()) {
                        return packageName + "." + simpleType + "." + methodName;
                    }
                    return simpleType + "." + methodName;
                }
            }

            // No variable: treat receiver as a type (static call), e.g. Collections.sort, DateUtils.format

            // 1) JDK static utility types
            String jdkCallerType = resolveJdkType(caller);
            if (jdkCallerType != null) {
                return jdkCallerType + "." + methodName;
            }

            // 2) Explicit import, e.g. import com.example.utils.DateUtils
            String importedCallerType = simpleToFullType.get(caller);
            if (importedCallerType != null) {
                return importedCallerType + "." + methodName;
            }

            // 3) Capitalized: likely same-package static class
            if (!caller.isEmpty() && Character.isUpperCase(caller.charAt(0)) && !packageName.isEmpty()) {
                return packageName + "." + caller + "." + methodName;
            }

            // Fallback: unqualified
            return caller + "." + methodName;
        }

        /**
         * Picks a best FQN from wildcard imports using name suffix heuristics.
         * <ol>
         *   <li>Types ending in Service: prefer ".service." over ".service.impl."</li>
         *   <li>Dao: prefer ".dao."</li>
         *   <li>Controller: prefer ".controller."</li>
         *   <li>Otherwise first match (none if no suffix rule)</li>
         * </ol>
         */
        private String findBestWildcardMatch(String simpleType) {
            if (wildcardImports.isEmpty()) {
                return null;
            }

            // Suffix -> package substring to prefer
            Map<String, String> typeSuffixToPackagePattern = new HashMap<>();
            typeSuffixToPackagePattern.put("Service", ".service.");
            typeSuffixToPackagePattern.put("Dao", ".dao.");
            typeSuffixToPackagePattern.put("Controller", ".controller.");
            typeSuffixToPackagePattern.put("Repository", ".repository.");
            typeSuffixToPackagePattern.put("Mapper", ".mapper.");

            // Match suffix
            for (Map.Entry<String, String> entry : typeSuffixToPackagePattern.entrySet()) {
                String suffix = entry.getKey();
                String packagePattern = entry.getValue();

                if (simpleType.endsWith(suffix)) {
                    // Prefer packages matching the pattern, excluding .impl. first
                    for (String wildcardPackage : wildcardImports) {
                        if (wildcardPackage.contains(packagePattern) && !wildcardPackage.contains(".impl.")) {
                            return wildcardPackage + "." + simpleType;
                        }
                    }
                    // Then allow impl
                    for (String wildcardPackage : wildcardImports) {
                        if (wildcardPackage.contains(packagePattern)) {
                            return wildcardPackage + "." + simpleType;
                        }
                    }
                }
            }

            // No suffix rule matched: do not guess; caller may use same-package resolution
            return null;
        }

        /**
         * Simplifies an expression to its last identifier segment.
         */
        private String simplifyExpression(String expression) {
            // Strip calls: getUserService() -> getUserService
            expression = expression.replaceAll("\\(.*?\\)", "");

            // Last segment after the final dot
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
     * Parses the whole project and returns all discovered methods.
     */
    public List<MethodInfo> parseProject() {
        long startTime = System.currentTimeMillis();
        System.out.println("\n========== Parsing project ==========");
        System.out.println("Project path: " + projectRoot);

        // Collect all .java files
        List<File> javaFiles = findJavaFiles(new File(projectRoot));
        System.out.println("Found " + javaFiles.size() + " Java file(s)");

        // Parse each file
        List<MethodInfo> allMethods = new ArrayList<>();
        int successCount = 0;
        int failCount = 0;

        for (File javaFile : javaFiles) {
            try {
                List<MethodInfo> methods = extractJavaMethods(javaFile);
                allMethods.addAll(methods);
                successCount++;
            } catch (Exception e) {
                System.err.println("  [FAIL] Could not parse file: " + javaFile.getPath() + " - " + e.getMessage());
                failCount++;
            }
        }

        long totalTime = (System.currentTimeMillis() - startTime) / 1000;
        System.out.println("\n========== Parse finished ==========");
        System.out.println("Succeeded: " + successCount + " file(s)");
        System.out.println("Failed: " + failCount + " file(s)");
        System.out.println("Methods extracted: " + allMethods.size());
        System.out.println("Elapsed: " + totalTime + " s\n");

        return allMethods;
    }

    /**
     * Extracts method metadata from a single Java file using the Eclipse JDT AST.
     */
    private List<MethodInfo> extractJavaMethods(File javaFile) {
        List<MethodInfo> methods = new ArrayList<>();

        try {
            // Read source
            String source = new String(Files.readAllBytes(javaFile.toPath()));

            // AST
            ASTParser parser = ASTParser.newParser(AST.JLS17);
            parser.setSource(source.toCharArray());
            parser.setKind(ASTParser.K_COMPILATION_UNIT);
            parser.setResolveBindings(false);  // no binding resolution (faster)

            CompilationUnit cu = (CompilationUnit) parser.createAST(null);

            // Package
            String packageName = "";
            if (cu.getPackage() != null) {
                packageName = cu.getPackage().getName().getFullyQualifiedName();
            }

            TypeResolver typeResolver = new TypeResolver();
            typeResolver.setPackageName(packageName);

            // Pass 1: imports
            List<?> imports = cu.imports();
            for (Object importObj : imports) {
                if (importObj instanceof ImportDeclaration importDecl) {
                    String importName = importDecl.getName().getFullyQualifiedName();

                    if (importDecl.isStatic()) {
                        // import static com.example.Utils.formatDate
                        if (!importDecl.isOnDemand()) {
                            typeResolver.addStaticMethodImport(importName);
                        }
                        // import static com.example.Utils.* is ignored
                    } else if (!importDecl.isOnDemand()) {  // single type import
                        typeResolver.addImport(importName);
                    } else {  // on-demand import
                        typeResolver.addWildcardImport(importName);
                    }
                }
            }

            // Visitor: methods
            final String finalPackageName = packageName;
            final TypeResolver finalTypeResolver = typeResolver;

            cu.accept(new ASTVisitor() {
                private String currentClassName = "";
                private String currentFullClassName = "";
                private String currentClassRequestMapping = "";  // class-level @RequestMapping path
                private TypeResolver currentTypeResolver = new TypeResolver();

                @Override
                public boolean visit(TypeDeclaration node) {
                    // Inner class
                    boolean isInnerClass = node.getParent() instanceof TypeDeclaration;

                    if (isInnerClass) {
                        // Map inner type; do not visit children (avoids methods attributed to outer class)
                        String innerClassName = node.getName().getIdentifier();
                        String fullInnerClassName = currentFullClassName + "." + innerClassName;
                        currentTypeResolver.simpleToFullType.put(innerClassName, fullInnerClassName);
                        return false;
                    }

                    // Top-level (or direct member) type
                    currentClassName = node.getName().getIdentifier();
                    currentFullClassName = finalPackageName.isEmpty()
                            ? currentClassName
                            : finalPackageName + "." + currentClassName;

                    currentClassRequestMapping = extractClassRequestMapping(node);

                    // Per-class resolver (inherits compilation-unit imports)
                    currentTypeResolver = new TypeResolver();
                    currentTypeResolver.setPackageName(finalPackageName);

                    currentTypeResolver.simpleToFullType.putAll(finalTypeResolver.simpleToFullType);

                    currentTypeResolver.wildcardImports.addAll(finalTypeResolver.wildcardImports);

                    currentTypeResolver.staticMethodImports.putAll(finalTypeResolver.staticMethodImports);

                    // Member types (for name resolution)
                    for (Object typeObj : node.getTypes()) {
                        if (typeObj instanceof TypeDeclaration innerType) {
                            String innerClassName = innerType.getName().getIdentifier();
                            String fullInnerClassName = currentFullClassName + "." + innerClassName;
                            currentTypeResolver.simpleToFullType.put(innerClassName, fullInnerClassName);
                        }
                    }

                    // Field types
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
                        // Inner enum: map only; do not visit children
                        String innerEnumName = node.getName().getIdentifier();
                        String fullInnerEnumName = currentFullClassName + "." + innerEnumName;
                        currentTypeResolver.simpleToFullType.put(innerEnumName, fullInnerEnumName);
                        return false;
                    }

                    // Top-level enum
                    currentClassName = node.getName().getIdentifier();
                    currentFullClassName = finalPackageName.isEmpty()
                            ? currentClassName
                            : finalPackageName + "." + currentClassName;

                    // Enums usually have no @RequestMapping
                    currentClassRequestMapping = "";

                    // Resolver for this enum
                    currentTypeResolver = new TypeResolver();
                    currentTypeResolver.setPackageName(finalPackageName);
                    currentTypeResolver.simpleToFullType.putAll(finalTypeResolver.simpleToFullType);
                    currentTypeResolver.wildcardImports.addAll(finalTypeResolver.wildcardImports);
                    currentTypeResolver.staticMethodImports.putAll(finalTypeResolver.staticMethodImports);

                    // Enum constant / field types
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
                        // Method-scoped copy of the resolver
                        TypeResolver methodTypeResolver = new TypeResolver();
                        methodTypeResolver.setPackageName(finalPackageName);
                        methodTypeResolver.setCurrentFullClassName(currentFullClassName);

                        methodTypeResolver.variableToSimpleType.putAll(currentTypeResolver.variableToSimpleType);
                        methodTypeResolver.simpleToFullType.putAll(currentTypeResolver.simpleToFullType);

                        methodTypeResolver.wildcardImports.addAll(currentTypeResolver.wildcardImports);

                        methodTypeResolver.staticMethodImports.putAll(currentTypeResolver.staticMethodImports);

                        // Parameters
                        List<?> parameters = node.parameters();
                        for (Object param : parameters) {
                            if (param instanceof SingleVariableDeclaration svd) {
                                String paramName = svd.getName().getIdentifier();
                                String paramType = svd.getType().toString();
                                methodTypeResolver.addVariable(paramName, paramType);
                            }
                        }

                        // Local variables
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
                                // for (Order o : orders)
                                SingleVariableDeclaration param = forStmt.getParameter();
                                String varType = param.getType().toString();
                                String varName = param.getName().getIdentifier();
                                methodTypeResolver.addVariable(varName, varType);
                                return super.visit(forStmt);
                            }

                            @Override
                            public boolean visit(PatternInstanceofExpression node) {
                                // Pattern binding: obj instanceof List<?> list
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

                        // Signature string
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

                        info.setStartLine(cu.getLineNumber(node.getStartPosition()));
                        info.setEndLine(cu.getLineNumber(node.getStartPosition() + node.getLength()));

                        info.setMethodBody(node.toString());

                        Javadoc javadoc = node.getJavadoc();
                        if (javadoc != null) {
                            info.setJavadoc(javadoc.toString());
                        } else {
                            info.setJavadoc("");
                        }

                        info.setCalledMethods(extractMethodCalls(node, methodTypeResolver));

                        extractApiAnnotations(node, info, currentClassRequestMapping);

                        methods.add(info);
                    } catch (Exception e) {
                        System.err.println("  [WARN] Failed to parse method: " + currentClassName + "." + node.getName().getIdentifier());
                    }
                    return super.visit(node);
                }
            });

        } catch (Exception e) {
            System.err.println("  [FAIL] Could not parse file: " + javaFile.getPath() + " - " + e.getMessage());
        }

        return methods;
    }

    /**
     * Collects callee signatures in the method body using the type resolver.
     */
    private List<String> extractMethodCalls(MethodDeclaration methodNode, TypeResolver typeResolver) {
        List<String> calledMethods = new ArrayList<>();

        methodNode.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodInvocation node) {
                try {
                    String methodName = node.getName().getIdentifier();
                    String expression = node.getExpression() != null ? node.getExpression().toString() : "";

                    String resolvedCall = typeResolver.resolveMethodCall(expression, methodName);

                    // Dedupe
                    if (!calledMethods.contains(resolvedCall)) {
                        calledMethods.add(resolvedCall);
                    }
                } catch (Exception e) {
                    // ignore resolution errors
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
                    // ignore
                }
                return super.visit(node);
            }

            @Override
            public boolean visit(org.eclipse.jdt.core.dom.ClassInstanceCreation node) {
                try {
                    org.eclipse.jdt.core.dom.Type type = node.getType();
                    String typeName = type.toString();

                    // Skip JDK types
                    if (isJdkClass(typeName)) {
                        return super.visit(node);
                    }

                    String fullClassName = typeResolver.resolveType(typeName);
                    String constructorCall = "new:" + fullClassName;

                    // Dedupe
                    if (!calledMethods.contains(constructorCall)) {
                        calledMethods.add(constructorCall);
                    }
                } catch (Exception e) {
                    // ignore
                }
                return super.visit(node);
            }
        });

        return calledMethods;
    }

    /**
     * Returns true if the type looks like a JDK / standard library type.
     */
    private boolean isJdkClass(String className) {
        // Strip generics
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
     * Reads the class-level {@code @RequestMapping} path, if any.
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
                    return extractPathFromAnnotation(annotationStr);
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return "";
    }

    /**
     * Extracts Spring web mapping annotations on the method and combines with the class path.
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
                        // method= on @RequestMapping not fully parsed
                        httpMethod = "REQUEST";
                    }

                    if (annotationName.contains("ApiOperation")) {
                        apiOperation = annotationStr;
                    }
                }
            }

            String fullPath = buildFullApiPath(classRequestMapping, methodMapping, httpMethod);

            info.setApiMapping(fullPath.isEmpty() ? "" : fullPath);
            info.setApiOperation(apiOperation.isEmpty() ? "" : apiOperation);

        } catch (Exception e) {
            info.setApiMapping("");
            info.setApiOperation("");
        }
    }

    /**
     * Parses the first path literal from a mapping annotation string, e.g. {@code @PostMapping("/start")} -> {@code /start}.
     */
    private String extractPathFromAnnotation(String annotationStr) {
        if (annotationStr == null || annotationStr.isEmpty()) {
            return "";
        }

        // @XxxMapping("/path") or @XxxMapping(value = "/path")
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
     * Builds a display string like {@code POST /api/analysis/start}.
     */
    private String buildFullApiPath(String classPath, String methodPath, String httpMethod) {
        if (httpMethod.isEmpty()) {
            return "";
        }

        StringBuilder fullPath = new StringBuilder();
        fullPath.append(httpMethod).append(" ");

        if (!classPath.isEmpty()) {
            if (!classPath.startsWith("/")) {
                fullPath.append("/");
            }
            fullPath.append(classPath);
        }

        if (!methodPath.isEmpty()) {
            if (!methodPath.startsWith("/") && !fullPath.toString().endsWith("/")) {
                fullPath.append("/");
            }
            fullPath.append(methodPath);
        }

        // Empty if only verb and no paths (e.g. bare @PostMapping with no value)
        if (fullPath.toString().equals(httpMethod + " ")) {
            return "";
        }

        return fullPath.toString();
    }

    /**
     * Walks the project tree for {@code .java} files (skips common build dirs).
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
            System.err.println("Failed to list Java files: " + e.getMessage());
        }
        return javaFiles;
    }

    /**
     * Path of the file relative to the project root.
     */
    private String getRelativePath(File file) {
        return Paths.get(projectRoot).relativize(file.toPath()).toString();
    }


    /**
     * Builds reverse edges ({@code calledBy}) from {@code calledMethods}, including interface-to-impl propagation
     * so callers through an interface are reflected on implementing methods.
     *
     * @param methods all parsed methods
     */
    public void buildCalledByRelations(List<MethodInfo> methods) {
        // methodKey (FQN.method) -> overloads
        Map<String, List<MethodInfo>> methodMap = new HashMap<>();
        for (MethodInfo method : methods) {
            String methodKey = method.getFullClassName() + "." + method.getMethodName();
            methodMap.computeIfAbsent(methodKey, k -> new ArrayList<>()).add(method);
            method.setCalledBy(new ArrayList<>());
        }

        // interface method key -> impl method keys
        Map<String, List<String>> interfaceToImplMap = buildInterfaceToImplementationMap(methods);

        System.out.println("[OK] Resolved interface-to-impl mappings for " + interfaceToImplMap.size() + " interface method(s)");

        // Reverse edges
        for (MethodInfo caller : methods) {
            if (caller.getCalledMethods() == null || caller.getCalledMethods().isEmpty()) {
                continue;
            }

            String callerKey = caller.getFullClassName() + "." + caller.getMethodName();

            for (String calledMethod : caller.getCalledMethods()) {
                List<MethodInfo> callees = methodMap.get(calledMethod);
                if (callees != null) {
                    for (MethodInfo callee : callees) {
                        if (!callee.getCalledBy().contains(callerKey)) {
                            callee.getCalledBy().add(callerKey);
                        }
                    }

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

        long methodsWithCallers = methods.stream()
                .filter(m -> m.getCalledBy() != null && !m.getCalledBy().isEmpty())
                .count();
        System.out.println("[OK] Built reverse call edges; " + methodsWithCallers + " method(s) have at least one caller");
    }

    /**
     * Maps interface method keys to implementing method keys from {@code implements} clauses.
     *
     * @param methods all methods
     * @return interface key -> list of impl keys
     */
    private Map<String, List<String>> buildInterfaceToImplementationMap(List<MethodInfo> methods) {
        Map<String, List<String>> interfaceToImplMap = new HashMap<>();

        Map<String, List<MethodInfo>> classMethods = new HashMap<>();
        for (MethodInfo method : methods) {
            classMethods.computeIfAbsent(method.getFullClassName(), k -> new ArrayList<>()).add(method);
        }

        Map<String, List<String>> classToInterfaces = parseClassImplementsRelations();

        // Per matching method name on impl vs interface
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

                for (MethodInfo interfaceMethod : interfaceMethods) {
                    String interfaceMethodKey = interfaceMethod.getFullClassName() + "." + interfaceMethod.getMethodName();

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
     * Returns implementing class FQN -> list of interface FQNs from {@code implements} clauses.
     */
    private Map<String, List<String>> parseClassImplementsRelations() {
        Map<String, List<String>> classToInterfaces = new HashMap<>();

        try (var stream = Files.walk(Paths.get(projectRoot))) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.toString().contains("target"))
                    .filter(p -> !p.toString().contains("build"))
                    .forEach(javaFile -> {
                        try {
                            parseClassImplements(javaFile.toFile(), classToInterfaces);
                        } catch (Exception e) {
                            // ignore parse errors
                        }
                    });
        } catch (IOException e) {
            System.err.println("Failed to walk project files: " + e.getMessage());
        }

        return classToInterfaces;
    }

    /**
     * Parses {@code implements} for one compilation unit.
     */
    private void parseClassImplements(File javaFile, Map<String, List<String>> classToInterfaces) {
        try {
            String source = new String(Files.readAllBytes(javaFile.toPath()));
            ASTParser parser = ASTParser.newParser(AST.JLS17);
            parser.setSource(source.toCharArray());
            parser.setKind(ASTParser.K_COMPILATION_UNIT);

            CompilationUnit cu = (CompilationUnit) parser.createAST(null);

            String packageName = "";
            if (cu.getPackage() != null) {
                packageName = cu.getPackage().getName().getFullyQualifiedName();
            }

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

            cu.accept(new ASTVisitor() {
                @Override
                public boolean visit(TypeDeclaration node) {
                    String className = node.getName().getIdentifier();
                    String fullClassName = finalPackageName.isEmpty() ? className : finalPackageName + "." + className;

                    List<?> superInterfaces = node.superInterfaceTypes();
                    if (superInterfaces != null && !superInterfaces.isEmpty()) {
                        List<String> interfaceNames = new ArrayList<>();

                        for (Object interfaceObj : superInterfaces) {
                            // Strip type args, e.g. OrderService<T> -> OrderService
                            String interfaceName = interfaceObj.toString().replaceAll("<.*>", "").trim();

                            String fullInterfaceName = resolveFullClassName(interfaceName, finalPackageName, finalImports);
                            interfaceNames.add(fullInterfaceName);
                        }

                        classToInterfaces.put(fullClassName, interfaceNames);
                    }

                    return super.visit(node);
                }
            });

        } catch (Exception e) {
            // ignore
        }
    }

    /**
     * Resolves a type reference to an FQN using imports and the current package.
     */
    private String resolveFullClassName(String simpleName, String currentPackage, Map<String, String> imports) {
        if (simpleName.contains(".")) {
            return simpleName;
        }

        if (imports.containsKey(simpleName)) {
            return imports.get(simpleName);
        }

        if (!currentPackage.isEmpty()) {
            return currentPackage + "." + simpleName;
        }

        return simpleName;
    }

}
