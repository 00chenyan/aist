package com.aist.dto;

import lombok.Data;

import java.util.List;

@Data
public class MethodInfo {
    private String filePath;
    private String packageName;
    private String className;
    private String fullClassName;
    private String methodName;
    private String methodSignature;
    private int startLine;
    private int endLine;
    private String methodBody;
    private String comment;
    private String javadoc;
    private List<String> calledMethods;  // Methods invoked by this method (format: fullyQualifiedClassName.methodName)
    private List<String> calledBy;  // Callers (format: fullyQualifiedClassName.methodName)
    private String apiMapping;  // HTTP mapping (PostMapping/GetMapping, etc.)
    private String apiOperation;  // ApiOperation summary/description text
}
