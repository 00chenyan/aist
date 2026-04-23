package com.aist.controller;

import com.aist.dto.CodeAnalyzeRequest;
import com.aist.service.CodeAnalyzeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 代码分析 Controller
 * 提供流式响应的代码分析接口
 */
@Slf4j
@RestController
@RequestMapping("/code/analyze")
public class CodeAnalyzeController {

    @Autowired
    private CodeAnalyzeService codeAnalyzeService;

    /**
     * SSE 超时时间（10分钟）
     */
    private static final long SSE_TIMEOUT = 30 * 60 * 1000L;

    /**
     * 非流式分析：一次返回完整结果（JSON）
     */
    @PostMapping(value = "/sync", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> analyzeSync(@RequestBody CodeAnalyzeRequest request) {
        Map<String, Object> body = codeAnalyzeService.analyzeBlocking(request);
        return ResponseEntity.ok(body);
    }

    /**
     * 流式分析代码
     * 使用 Server-Sent Events 返回分析过程和结果
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter analyzeStream(@RequestBody CodeAnalyzeRequest request) {
        log.info("收到代码分析请求: projectId={}, apiUrl={}, question={}",
                request.getProjectId(), request.getApiUrl(), request.getQuestion());

        // 创建 SSE 发射器
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);

        // 设置回调
        emitter.onCompletion(() -> log.info("SSE连接完成"));
        emitter.onTimeout(() -> log.warn("SSE连接超时"));
        emitter.onError(e -> log.error("SSE连接错误", e));

        // 异步执行分析
        new Thread(() -> {
            try {
                codeAnalyzeService.analyzeWithStream(request, emitter);
            } catch (Exception e) {
                log.error("分析执行失败", e);
                try {
                    emitter.completeWithError(e);
                } catch (Exception ex) {
                    log.error("发送错误失败", ex);
                }
            }
        }).start();

        return emitter;
    }

    /**
     * 创建新会话
     * 返回会话ID，用于多轮对话
     */
    @PostMapping("/session/create")
    public ResponseEntity<Map<String, Object>> createSession() {
        String sessionId = UUID.randomUUID().toString();
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "会话创建成功");
        result.put("sessionId", sessionId);
        return ResponseEntity.ok(result);
    }

    /**
     * 清理会话
     */
    @DeleteMapping("/session/{sessionId}")
    public ResponseEntity<Map<String, Object>> clearSession(@PathVariable String sessionId) {
        codeAnalyzeService.clearSession(sessionId);
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "会话已清理");
        return ResponseEntity.ok(result);
    }

}
