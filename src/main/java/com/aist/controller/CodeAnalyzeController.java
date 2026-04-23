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
 * HTTP API for code analysis (blocking JSON and SSE streaming).
 */
@Slf4j
@RestController
@RequestMapping("/code/analyze")
public class CodeAnalyzeController {

    @Autowired
    private CodeAnalyzeService codeAnalyzeService;

    /** SSE connection timeout: 30 minutes. */
    private static final long SSE_TIMEOUT = 30 * 60 * 1000L;

    /**
     * Synchronous analysis: single JSON response.
     */
    @PostMapping(value = "/sync", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> analyzeSync(@RequestBody CodeAnalyzeRequest request) {
        Map<String, Object> body = codeAnalyzeService.analyzeBlocking(request);
        return ResponseEntity.ok(body);
    }

    /**
     * Streaming analysis with Server-Sent Events.
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter analyzeStream(@RequestBody CodeAnalyzeRequest request) {
        log.info("Code analysis request: projectId={}, apiUrl={}, question={}",
                request.getProjectId(), request.getApiUrl(), request.getQuestion());

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);

        emitter.onCompletion(() -> log.info("SSE connection completed"));
        emitter.onTimeout(() -> log.warn("SSE connection timed out"));
        emitter.onError(e -> log.error("SSE connection error", e));

        new Thread(() -> {
            try {
                codeAnalyzeService.analyzeWithStream(request, emitter);
            } catch (Exception e) {
                log.error("Analysis execution failed", e);
                try {
                    emitter.completeWithError(e);
                } catch (Exception ex) {
                    log.error("Failed to complete SSE with error", ex);
                }
            }
        }).start();

        return emitter;
    }

    /**
     * Create a new session id for multi-turn analysis.
     */
    @PostMapping("/session/create")
    public ResponseEntity<Map<String, Object>> createSession() {
        String sessionId = UUID.randomUUID().toString();
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "Session created");
        result.put("sessionId", sessionId);
        return ResponseEntity.ok(result);
    }

    /**
     * Remove server-side state for a session.
     */
    @DeleteMapping("/session/{sessionId}")
    public ResponseEntity<Map<String, Object>> clearSession(@PathVariable String sessionId) {
        codeAnalyzeService.clearSession(sessionId);
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "Session cleared");
        return ResponseEntity.ok(result);
    }

}
