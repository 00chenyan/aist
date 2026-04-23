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
    private List<String> calledMethods;  // 该方法调用的其他方法列表（格式：完整类名.方法名）
    private List<String> calledBy;  // 调用者信息列表（格式：完整类名.方法名），记录当前方法被哪些其他方法调用
    private String apiMapping;  // 接口映射信息（PostMapping/GetMapping 等）
    private String apiOperation;  // API 操作信息（ApiOperation 注解内容）
}
