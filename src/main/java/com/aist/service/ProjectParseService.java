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
 * Project parsing service.
 * Parses source, builds method index and call graph.
 * Used internally by tools; not exposed directly to the LLM.
 */
@Slf4j
@Service
public class ProjectParseService {

    private static final String CACHE_FILE_NAME = "code_analysis_cache.json";
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Ensures the project is parsed: parses if needed, otherwise no-op.
     *
     * @param context analysis context
     * @throws Exception if parsing fails
     */
    public void ensureProjectParsed(CodeAnalyzeContextDTO context) throws Exception {
        if (context.getAllMethods() != null && !context.getAllMethods().isEmpty()) {
            log.debug("Project already parsed, skipping");
            return;
        }

        parseProject(context);
    }

    /**
     * Parses project source.
     */
    private void parseProject(CodeAnalyzeContextDTO context) throws Exception {
        String projectPath = context.getProjectPath();
        log.info("Starting project parse: {}", projectPath);
        context.notifyStep("Parsing project source...");

        List<MethodInfo> methods = loadFromCache(projectPath);

        if (methods == null) {
            log.info("Cache missing or stale, parsing project...");
            context.notifyStep("No cache found; parsing project (may take a few minutes)...");
            JdtCodeIndexer indexer = new JdtCodeIndexer(projectPath);
            methods = indexer.parseProject();
            indexer.buildCalledByRelations(methods);

            saveToCache(projectPath, methods);
        } else {
            context.notifyStep("Loading project data from cache...");
        }

        context.setAllMethods(methods);
        context.setMethodMap(buildMethodMap(methods));
        context.setClassToBasePath(extractClassMappings(methods));

        context.notifyStep("Parse finished, " + methods.size() + " methods");
        log.info("Project parse finished, {} methods", methods.size());
    }

    /**
     * Loads method list from cache if valid.
     */
    private List<MethodInfo> loadFromCache(String projectPath) {
        File cacheFile = new File(projectPath, CACHE_FILE_NAME);
        if (!cacheFile.exists()) {
            return null;
        }

        long cacheAge = System.currentTimeMillis() - cacheFile.lastModified();
        if (cacheAge > 24 * 60 * 60 * 1000) {
            log.info("Cache expired ({} hours old)", cacheAge / 3600000);
            return null;
        }

        try {
            List<MethodInfo> methods = objectMapper.readValue(cacheFile,
                    new TypeReference<List<MethodInfo>>() {});
            log.info("Loaded {} methods from cache", methods.size());
            return methods;
        } catch (IOException e) {
            log.warn("Failed to read cache: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Writes method list to cache.
     */
    private void saveToCache(String projectPath, List<MethodInfo> methods) {
        File cacheFile = new File(projectPath, CACHE_FILE_NAME);
        try {
            objectMapper.writeValue(cacheFile, methods);
            log.info("Cache saved: {}", cacheFile.getAbsolutePath());
        } catch (IOException e) {
            log.warn("Failed to save cache: {}", e.getMessage());
        }
    }

    /**
     * Builds method lookup map.
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
     * Extracts class-level base RequestMapping paths.
     */
    private Map<String, String> extractClassMappings(List<MethodInfo> methods) {
        Map<String, String> classToBasePath = new HashMap<>();
        for (MethodInfo method : methods) {
            String apiMapping = method.getApiMapping();
            if (apiMapping != null && !apiMapping.isEmpty()) {
                String basePath = extractBasePath(apiMapping);
                if (basePath != null && !classToBasePath.containsKey(method.getClassName())) {
                    classToBasePath.put(method.getClassName(), basePath);
                }
            }
        }
        return classToBasePath;
    }

    /**
     * Returns first path segment as base path.
     */
    private String extractBasePath(String apiMapping) {
        if (apiMapping == null || apiMapping.isEmpty()) {
            return null;
        }
        String path = apiMapping.startsWith("/") ? apiMapping.substring(1) : apiMapping;
        int slashIndex = path.indexOf("/");
        if (slashIndex > 0) {
            return "/" + path.substring(0, slashIndex);
        }
        return "/" + path;
    }
}
