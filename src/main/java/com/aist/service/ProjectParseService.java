package com.aist.service;

import com.aist.dto.CodeAnalyzeContextDTO;
import com.aist.dto.MethodInfo;
import com.aist.util.JdtCodeIndexer;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 项目解析服务
 * 负责解析项目代码，构建方法索引和调用关系
 * 供其他工具内部调用，不直接暴露给 LLM
 */
@Slf4j
@Service
public class ProjectParseService {

    private static final String CACHE_FILE_NAME = "code_analysis_cache.json";
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 确保项目已解析
     * 如果未解析，则自动解析；如果已解析，则直接返回
     *
     * @param context 分析上下文
     * @throws Exception 解析失败时抛出异常
     */
    public void ensureProjectParsed(CodeAnalyzeContextDTO context) throws Exception {
        // 检查是否已经解析过
        if (context.getAllMethods() != null && !context.getAllMethods().isEmpty()) {
            log.debug("项目已解析，跳过解析步骤");
            return;
        }

        // 执行解析
        parseProject(context);
    }

    /**
     * 解析项目代码
     */
    private void parseProject(CodeAnalyzeContextDTO context) throws Exception {
        String projectPath = context.getProjectPath();
        log.info("开始解析项目: {}", projectPath);
        context.notifyStep("正在解析项目代码...");

        // 尝试从缓存加载
        List<MethodInfo> methods = loadFromCache(projectPath);

        if (methods == null) {
            // 重新解析
            log.info("缓存不存在或已过期，重新解析项目...");
            context.notifyStep("缓存不存在，正在解析项目（可能需要几分钟）...");
            JdtCodeIndexer indexer = new JdtCodeIndexer(projectPath);
            methods = indexer.parseProject();
            indexer.buildCalledByRelations(methods);

            // 保存缓存
            saveToCache(projectPath, methods);
        } else {
            context.notifyStep("从缓存加载项目数据...");
        }

        // 设置到上下文
        context.setAllMethods(methods);
        context.setMethodMap(buildMethodMap(methods));
        context.setClassToBasePath(extractClassMappings(methods));

        context.notifyStep("解析完成，共 " + methods.size() + " 个方法");
        log.info("项目解析完成，共 {} 个方法", methods.size());
    }

    /**
     * 从缓存加载
     */
    private List<MethodInfo> loadFromCache(String projectPath) {
        File cacheFile = new File(projectPath, CACHE_FILE_NAME);
        if (!cacheFile.exists()) {
            return null;
        }

        // 检查缓存是否过期（24小时）
        long cacheAge = System.currentTimeMillis() - cacheFile.lastModified();
        if (cacheAge > 24 * 60 * 60 * 1000) {
            log.info("缓存已过期（{}小时前）", cacheAge / 3600000);
            return null;
        }

        try {
            List<MethodInfo> methods = objectMapper.readValue(cacheFile,
                    new TypeReference<List<MethodInfo>>() {});
            log.info("从缓存加载 {} 个方法", methods.size());
            return methods;
        } catch (IOException e) {
            log.warn("读取缓存失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 保存到缓存
     */
    private void saveToCache(String projectPath, List<MethodInfo> methods) {
        File cacheFile = new File(projectPath, CACHE_FILE_NAME);
        try {
            objectMapper.writeValue(cacheFile, methods);
            log.info("缓存已保存: {}", cacheFile.getAbsolutePath());
        } catch (IOException e) {
            log.warn("保存缓存失败: {}", e.getMessage());
        }
    }

    /**
     * 构建方法映射表
     */
    private Map<String, MethodInfo> buildMethodMap(List<MethodInfo> methods) {
        Map<String, MethodInfo> map = new HashMap<>();
        for (MethodInfo method : methods) {
            String key = method.getFullClassName() + "." + method.getMethodName();
            map.put(key, method);
        }
        return map;
    }

    /**
     * 提取类到基础路径的映射
     */
    private Map<String, String> extractClassMappings(List<MethodInfo> methods) {
        Map<String, String> classToBasePath = new HashMap<>();
        for (MethodInfo method : methods) {
            // 从apiMapping中提取类级别的基础路径
            String apiMapping = method.getApiMapping();
            if (apiMapping != null && !apiMapping.isEmpty()) {
                // 提取基础路径（第一个路径段）
                String basePath = extractBasePath(apiMapping);
                if (basePath != null && !classToBasePath.containsKey(method.getClassName())) {
                    classToBasePath.put(method.getClassName(), basePath);
                }
            }
        }
        return classToBasePath;
    }

    /**
     * 提取基础路径
     */
    private String extractBasePath(String apiMapping) {
        if (apiMapping == null || apiMapping.isEmpty()) {
            return null;
        }
        // 去除开头的/
        String path = apiMapping.startsWith("/") ? apiMapping.substring(1) : apiMapping;
        // 取第一个路径段
        int slashIndex = path.indexOf("/");
        if (slashIndex > 0) {
            return "/" + path.substring(0, slashIndex);
        }
        return "/" + path;
    }
}

