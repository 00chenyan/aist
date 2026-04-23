package com.aist.service;

import com.aist.config.AistConfig;
import com.aist.callback.AnalyzeCallback;
import com.aist.enums.QuestionTypeEnum;
import com.aist.mapper.ConversationRecordMapper;
import com.aist.dto.CodeAnalyzeEvent;
import com.aist.dto.CodeAnalyzeRequest;
import com.aist.enums.SessionTypeEnum;
import com.aist.filter.RelevanceFilter;
import com.aist.dto.CodeAnalyzeContextDTO;
import com.aist.model.ConversationRecord;
import com.aist.tool.ToolRegistry;
import com.aist.tool.ToolRequest;
import com.aist.tool.ToolResult;
import com.aist.util.DeepSeekUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Code analysis orchestration service.
 */
@Slf4j
@Service
public class CodeAnalyzeService {

    @Autowired
    private AistConfig aistConfig;

    @Autowired
    private ToolRegistry toolRegistry;

    @Autowired
    private RelevanceFilter relevanceFilter;

    @Autowired
    private ConversationRecordMapper conversationRecordMapper;

    @Value("${deepseek.api.key:}")
    private String deepseekApiKey;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // Constants
    private static final int MAX_ROUNDS = 100;
    private static final int MAX_HISTORY_SIZE = 20;
    private static final int MAX_TOOL_RESULT_LENGTH = 8000;
    private static final Pattern TOOL_CALL_PATTERN = Pattern.compile(
            "\\[TOOL_CALL:([A-Z_]+):([^]]+)]",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern NEEDS_KEY_PATTERN = Pattern.compile("需求内容[：:]");
    private static final Pattern COMMIT_KEY_PATTERN = Pattern.compile("提交[iI][dD][：:]");

    /**
     * In-memory session store. TODO: persist in database.
     * Key: sessionId, value: analysis context. Each user has an isolated session (UUID).
     */
    private final Map<String, CodeAnalyzeContextDTO> sessionContexts = new ConcurrentHashMap<>();

    /**
     * Last access time per session (used for expiration).
     */
    private final Map<String, Long> sessionLastAccess = new ConcurrentHashMap<>();

    /**
     * Session TTL: 30 minutes.
     */
    private static final long SESSION_EXPIRE_MS = 30 * 60 * 1000;

    private void validateAnalyzeRequest(CodeAnalyzeRequest request) {
        if (request.getQuestion() == null || request.getQuestion().trim().isEmpty()) {
            throw new IllegalArgumentException("Question must not be empty");
        }
        String projectPath = aistConfig.getCodeRepo().getPath();
        if (projectPath == null || projectPath.isEmpty()) {
            throw new IllegalStateException("Code repository path is not configured");
        }
        if (!new File(projectPath).exists()) {
            throw new IllegalStateException("Code repository path does not exist: " + projectPath);
        }
    }

    private void completeEmitter(SseEmitter emitter) {
        if (emitter == null) {
            return;
        }
        try {
            emitter.complete();
        } catch (Exception e) {
            log.debug("Ignoring emitter.complete failure: {}", e.getMessage());
        }
    }

    /**
     * Non-streaming analysis: one request returns the full result (same pipeline as /stream, no SSE).
     */
    public Map<String, Object> analyzeBlocking(CodeAnalyzeRequest request) {
        Map<String, Object> result = new HashMap<>();
        try {
            validateAnalyzeRequest(request);
            CodeAnalyzeContextDTO context = runAnalyzePipeline(request, null, true);
            if (context.getClarificationQuestionJson() != null) {
                result.put("success", true);
                result.put("eventType", CodeAnalyzeEvent.TYPE_QUESTION);
                result.put("data", context.getClarificationQuestionJson());
                return result;
            }
            String ans = context.getFinalAnswer();
            result.put("success", true);
            result.put("eventType", CodeAnalyzeEvent.TYPE_DONE);
            result.put("data", ans != null ? ans : "");
            return result;
        } catch (IllegalArgumentException | IllegalStateException e) {
            log.warn("Invalid analysis request: {}", e.getMessage());
            result.put("success", false);
            result.put("eventType", CodeAnalyzeEvent.TYPE_ERROR);
            result.put("message", e.getMessage());
            return result;
        } catch (Exception e) {
            log.error("Code analysis failed", e);
            result.put("success", false);
            result.put("eventType", CodeAnalyzeEvent.TYPE_ERROR);
            result.put("message", "Analysis failed: " + e.getMessage());
            return result;
        }
    }

    /**
     * Streamed code analysis.
     *
     * @param request HTTP request DTO
     * @param emitter SSE emitter
     */
    public void analyzeWithStream(CodeAnalyzeRequest request, SseEmitter emitter) {
        CodeAnalyzeContextDTO context = null;
        try {
            validateAnalyzeRequest(request);

            String projectPath = aistConfig.getCodeRepo().getPath();
            String projectName = new File(projectPath).getName();

            sendEventSafe(emitter, CodeAnalyzeEvent.start("开始分析项目: " + projectName));

            context = runAnalyzePipeline(request, emitter, false);

            if (context.getClarificationQuestionJson() != null) {
                completeEmitter(emitter);
                return;
            }

            String analysisResult = context.getFinalAnswer();
            sendEventSafe(emitter, CodeAnalyzeEvent.done(analysisResult != null ? analysisResult : "分析完成"));
            completeEmitter(emitter);

        } catch (IllegalArgumentException | IllegalStateException e) {
            log.warn("Invalid analysis request: {}", e.getMessage());
            sendEventSafe(emitter, CodeAnalyzeEvent.error(e.getMessage()));
            completeEmitter(emitter);
        } catch (Exception e) {
            log.error("Code analysis failed", e);
            try {
                sendEventSafe(emitter, CodeAnalyzeEvent.error("Analysis failed: " + e.getMessage()));
                completeEmitter(emitter);
            } catch (Exception ex) {
                log.error("Failed to send error event", ex);
            }
        } finally {
            // Placeholder: tear down per-session loggers for one-off runs without sessionId
            if (context != null
                    && context.getRequest() != null
                    && (context.getRequest().getSessionId() == null
                        || context.getRequest().getSessionId().trim().isEmpty())
                    ) {
            }
        }
    }

    /**
     * Build context and run LLM analysis (shared by streaming and blocking modes).
     */
    private CodeAnalyzeContextDTO runAnalyzePipeline(CodeAnalyzeRequest request, SseEmitter emitter, boolean blockingMode)
            throws Exception {
        String projectPath = aistConfig.getCodeRepo().getPath();
        String projectName = new File(projectPath).getName();

        cleanExpiredSessions();

        String sessionId = request.getSessionId();
        CodeAnalyzeContextDTO context;

        if (sessionId != null && sessionContexts.containsKey(sessionId)) {
            context = sessionContexts.get(sessionId);
            context.setRequest(request);
            if (!blockingMode) {
                sendEvent(emitter, CodeAnalyzeEvent.step("继续上次会话..."));
            }
        } else {
            context = createContext(request, projectPath, projectName);
            if (sessionId != null) {
                sessionContexts.put(sessionId, context);
            }
        }

        if (sessionId != null) {
            sessionLastAccess.put(sessionId, System.currentTimeMillis());
        }

        context.setBlockingMode(blockingMode);
        context.setClarificationQuestionJson(null);
        context.setCallback(createCallback(emitter));
        context.setEmitter(emitter);

        executeLlmAnalysis(context);
        return context;
    }

    /**
     * Create a new analysis context for a request.
     */
    private CodeAnalyzeContextDTO createContext(CodeAnalyzeRequest request, String projectPath, String projectName) {
        CodeAnalyzeContextDTO context = new CodeAnalyzeContextDTO();
        context.setRequest(request);
        context.setProjectName(projectName);
        context.setProjectPath(projectPath);
        context.setDeepseekApiKey(deepseekApiKey);
        context.setToolRegistry(toolRegistry);

        // Database (from aist.target-db)
        context.setDatabaseName(aistConfig.getTargetDb().getDefaultDatabase());
        context.setDbSourceName("dev");  // default datasource name

        context.setConversationHistory(new ArrayList<>());

        String sessionId = request.getSessionId() != null ? request.getSessionId() : UUID.randomUUID().toString();
        try {
        } catch (Exception e) {
            log.warn("Failed to initialize analysis logger: {}", e.getMessage());
        }

        return context;
    }

    /**
     * Build SSE event callback for the analysis pipeline.
     */
    private AnalyzeCallback createCallback(SseEmitter emitter) {
        return new AnalyzeCallback() {

            @Override
            public void onStep(String step) {
                sendEventSafe(emitter, CodeAnalyzeEvent.step(step));
            }

            @Override
            public void onContent(String text) {
                sendEventSafe(emitter, CodeAnalyzeEvent.content(text));
            }


            @Override
            public void onError(String error) {
                sendEventSafe(emitter, CodeAnalyzeEvent.error(error));
            }
        };
    }

    /**
     * Send an SSE event; swallow exceptions.
     */
    private void sendEventSafe(SseEmitter emitter, CodeAnalyzeEvent event) {
        if (emitter == null) {
            return;
        }
        try {
            sendEvent(emitter, event);
        } catch (Exception e) {
        }
    }

    /**
     * Send an SSE event (errors propagate to caller).
     */
    private void sendEvent(SseEmitter emitter, CodeAnalyzeEvent event) throws IOException {
        if (emitter == null) {
            return;
        }
        String json = objectMapper.writeValueAsString(event);
        emitter.send(SseEmitter.event()
                .name(event.getType())
                .data(json));
    }

    /**
     * Remove a session and its state.
     */
    public void clearSession(String sessionId) {
        CodeAnalyzeContextDTO ctx = sessionContexts.remove(sessionId);
        sessionLastAccess.remove(sessionId);
    }

    /**
     * Drop sessions that have not been accessed within {@link #SESSION_EXPIRE_MS}.
     */
    private void cleanExpiredSessions() {
        long now = System.currentTimeMillis();
        sessionLastAccess.entrySet().removeIf(entry -> {
            if (now - entry.getValue() > SESSION_EXPIRE_MS) {
                CodeAnalyzeContextDTO ctx = sessionContexts.remove(entry.getKey());
                log.info("Evicted expired session: {}", entry.getKey());
                return true;
            }
            return false;
        });
    }



    // ==================== LLM analysis ====================

    /**
     * Entry point for LLM analysis.
     * If the question is an impact-scope analysis with both requirement content and commit ID, run a split
     * (A/B) pass and merge; otherwise run a single pass.
     */
    private void executeLlmAnalysis(CodeAnalyzeContextDTO context) throws Exception {
        log.info("Starting LLM analysis");

        String question = context.getQuestion();

        conversationRecord(context.getRequest().getSessionId(), question,
                QuestionTypeEnum.QUESTION.getCode(), SessionTypeEnum.QUESTION.getCode());

        String finalAnswer;

        if (isImpactAnalysisRequest(question)) {
            String[] parts = extractImpactParts(question);
            if (parts != null) {
                // parts[0] prefix, [1] requirement body, [2] commit id
                finalAnswer = executeSplitImpactAnalysis(context, parts[0], parts[1], parts[2]);
            } else {
                finalAnswer = executeNormalLlmAnalysis(context);
            }
        } else {
            finalAnswer = executeNormalLlmAnalysis(context);
        }

        if (finalAnswer != null) {
            context.setFinalAnswer(finalAnswer);
        }

        String recorded = context.getFinalAnswer() != null ? context.getFinalAnswer() : "";
        conversationRecord(context.getRequest().getSessionId(), recorded,
                QuestionTypeEnum.DONE.getCode(), SessionTypeEnum.ANSWER.getCode());

        log.info("LLM analysis completed");
    }

    private boolean isImpactAnalysisRequest(String question) {
        return question != null && question.contains("影响范围");
    }

    /**
     * If both need key and commit key are present, returns [prefix, needs segment, commit id]; else null.
     */
    private String[] extractImpactParts(String question) {
        if (question == null) {
            return null;
        }
        Matcher needsMatcher = NEEDS_KEY_PATTERN.matcher(question);
        Matcher commitMatcher = COMMIT_KEY_PATTERN.matcher(question);

        boolean hasNeeds = needsMatcher.find();
        boolean hasCommit = commitMatcher.find();

        if (!hasNeeds || !hasCommit) {
            return null;
        }

        int needsKeyStart = needsMatcher.start();
        int needsContentStart = needsMatcher.end();
        int commitKeyStart = commitMatcher.start();
        int commitContentStart = commitMatcher.end();

        // Prefix: text before whichever keyword appears first
        int firstKeyStart = Math.min(needsKeyStart, commitKeyStart);
        String prefix = question.substring(0, firstKeyStart).trim();

        String needsContent;
        String commitId;

        if (needsKeyStart < commitKeyStart) {
            needsContent = question.substring(needsContentStart, commitKeyStart).trim();
            commitId = question.substring(commitContentStart).trim();
        } else {
            commitId = question.substring(commitContentStart, needsKeyStart).trim();
            needsContent = question.substring(needsContentStart).trim();
        }

        if (needsContent.isEmpty() || commitId.isEmpty()) {
            return null;
        }

        return new String[]{prefix, needsContent, commitId};
    }

    /**
     * Run split impact analysis: path A = requirement text, path B = commit, then merge via LLM.
     */
    private String executeSplitImpactAnalysis(CodeAnalyzeContextDTO context,
                                               String prefix, String needsContent, String commitId) throws Exception {
        String basePrefix = prefix.isEmpty() ? "" : prefix + "\n";

        // Path A: requirements content
        context.notifyStep("正在分析【需求内容】影响范围...");
        String questionA = basePrefix + "需求内容：" + needsContent;
        String resultA = runSubAnalysis(context, questionA, "【需求内容】");
        if (resultA == null) {
            resultA = "（需求内容分析未得到结果）";
        }

        // Path B: commit id
        context.notifyStep("正在分析【提交ID】影响范围...");
        String questionB = basePrefix + "提交id：" + commitId;
        String resultB = runSubAnalysis(context, questionB, "【提交ID】");
        if (resultB == null) {
            resultB = "（提交ID分析未得到结果）";
        }

        // Merge results from both paths
        context.notifyStep("正在综合A、B版本分析结果...");
        return combineImpactResults(context, resultA, resultB);
    }

    /**
     * Clone context with a new question and run a single analysis pass; returns the final text answer.
     */
    private String runSubAnalysis(CodeAnalyzeContextDTO originalContext,
                                   String subQuestion, String label) throws Exception {
        CodeAnalyzeRequest subRequest = new CodeAnalyzeRequest();
        subRequest.setProjectId(originalContext.getRequest().getProjectId());
        subRequest.setApiUrl(originalContext.getRequest().getApiUrl());
        subRequest.setQuestion(subQuestion);
        subRequest.setSessionId(originalContext.getRequest().getSessionId());

        CodeAnalyzeContextDTO subContext = new CodeAnalyzeContextDTO();
        subContext.setRequest(subRequest);
        subContext.setProjectName(originalContext.getProjectName());
        subContext.setProjectPath(originalContext.getProjectPath());
        subContext.setDeepseekApiKey(originalContext.getDeepseekApiKey());
        subContext.setToolRegistry(originalContext.getToolRegistry());
        subContext.setDatabaseName(originalContext.getDatabaseName());
        subContext.setDbSourceName(originalContext.getDbSourceName());
        subContext.setConversationHistory(new ArrayList<>());

        SseEmitter emitter = (SseEmitter) originalContext.getEmitter();
        subContext.setCallback(createLabeledCallback(emitter, label));
        subContext.setEmitter(emitter);

        return executeNormalLlmAnalysis(subContext);
    }

    /**
     * Callback that prefixes step/error lines with a label (for A/B sub-analyses).
     */
    private AnalyzeCallback createLabeledCallback(SseEmitter emitter, String label) {
        return new AnalyzeCallback() {

            @Override
            public void onStep(String step) {
                sendEventSafe(emitter, CodeAnalyzeEvent.step(label + " " + step));
            }

            @Override
            public void onContent(String text) {
                sendEventSafe(emitter, CodeAnalyzeEvent.content(text));
            }


            @Override
            public void onError(String error) {
                sendEventSafe(emitter, CodeAnalyzeEvent.error(label + " " + error));
            }
        };
    }

    /**
     * Ask DeepSeek to merge path A and path B into one impact-scope summary and compare test-point overlap.
     */
    private String combineImpactResults(CodeAnalyzeContextDTO context,
                                         String resultA, String resultB) throws Exception {
        DeepSeekUtil deepSeek = new DeepSeekUtil(context.getDeepseekApiKey());
        String systemPrompt = "请结合A和B版本的需求范围内容，输出一个包含A版和B版汇总的需求范围描述。并统及A、B版本含有测试点的异同数目";
        String userPrompt = "A版本影响范围分析结果：\n" + resultA + "\n\nB版本影响范围分析结果：\n" + resultB;

        List<DeepSeekUtil.Message> messages = new ArrayList<>();
        messages.add(new DeepSeekUtil.Message("system", systemPrompt));
        messages.add(new DeepSeekUtil.Message("user", userPrompt));
        log.info("Merged user prompt: {}", userPrompt);

        return deepSeek.chat(messages);
    }

    /**
     * Single-path LLM loop; returns the final answer text (persistence is up to the caller).
     */
    private String executeNormalLlmAnalysis(CodeAnalyzeContextDTO context) throws Exception {
        DeepSeekUtil deepSeek = new DeepSeekUtil(context.getDeepseekApiKey());

        List<DeepSeekUtil.Message> messages = context.getConversationHistory();
        boolean isFirstRound = messages.isEmpty();

        if (isFirstRound) {
            String systemPrompt = buildSystemPrompt();
            messages.add(new DeepSeekUtil.Message("system", systemPrompt));
        }

        String userPrompt = buildUserPrompt(context);
        messages.add(new DeepSeekUtil.Message("user", userPrompt));


        Set<String> executedTools = new HashSet<>();
        String finalAnswer = null;

        for (int round = 1; round <= MAX_ROUNDS; round++) {

            context.notifyStep("【第 " + round + " 轮分析】正在思考中...");

            String response = deepSeek.chat(messages);
            messages.add(new DeepSeekUtil.Message("assistant", response));


            String questionJson = extractQuestionJson(response);
            if (questionJson != null) {
                log.info("Clarification request detected: {}", questionJson);
                try {
                    questionJson = validateAndFixQuestionJson(questionJson);
                    log.info("Validated question JSON: {}", questionJson);

                    context.setClarificationQuestionJson(questionJson);
                    if (context.isBlockingMode()) {
                        context.setConversationHistory(messages);
                        log.info("Waiting for user answers (non-streaming)");
                        return null;
                    }

                    SseEmitter emitter = (SseEmitter) context.getEmitter();
                    if (emitter != null) {
                        sendEventSafe(emitter, CodeAnalyzeEvent.question(questionJson));
                        sendEventSafe(emitter, CodeAnalyzeEvent.done(""));
                        completeEmitter(emitter);
                    }

                    context.setConversationHistory(messages);
                    log.info("Waiting for user to answer questions");
                    return null;
                } catch (Exception e) {
                    log.error("Invalid question JSON: {}", questionJson, e);
                    context.notifyContent("问题格式错误，继续分析...\n\n");
                }
            }

            List<ToolRequest> toolRequests = parseToolRequests(response);

            if (toolRequests.isEmpty()) {
                finalAnswer = response;
                trimHistory(messages);
                break;
            }

            String summary = filterToolCalls(response);
            if (!summary.isEmpty()) {
                context.notifyContent(summary + "\n\n");
            }

            List<ToolRequest> pendingRequests = new ArrayList<>();
            for (ToolRequest req : toolRequests) {
                String key = req.getToolName() + ":" + req.getArgumentsString();
                if (!executedTools.contains(key)) {
                    pendingRequests.add(req);
                    executedTools.add(key);
                }
            }

            if (pendingRequests.isEmpty()) {
                messages.add(new DeepSeekUtil.Message("user",
                        "你请求的信息已在之前全部提供过，请基于现有信息给出最终答案。"));
                continue;
            }

            StringBuilder toolResultsMsg = new StringBuilder();
            toolResultsMsg.append("## 工具调用结果\n\n");

            for (ToolRequest req : pendingRequests) {

                ToolResult result = toolRegistry.executeTool(req, context);
                log.info(result.getResult());

                result = relevanceFilter.filter(result, context);


                toolResultsMsg.append("### ").append(req.getToolName())
                        .append("(").append(req.getArgumentsString()).append(")\n\n");

                String compressedResult = compressToolResult(result.getResult());
                toolResultsMsg.append(compressedResult).append("\n\n");
            }

            toolResultsMsg.append("请基于以上信息继续分析。如果信息足够，请给出最终答案；如果还需要更多信息，请继续使用工具。");
            messages.add(new DeepSeekUtil.Message("user", toolResultsMsg.toString()));
        }

        context.setConversationHistory(messages);
        return finalAnswer;
    }

    /**
     * Persist conversation text for the given session and classification.
     */
    private void conversationRecord(String sessionId, String context, int questionType, int sessionType) {
        try {
            ConversationRecord conversationRecord = new ConversationRecord();
            conversationRecord.setSessionId(sessionId);
            conversationRecord.setQuestionType(questionType);

            // Split on numbered-question line markers (matches Chinese UI pattern via Pattern below)
            List<String> questionList = new ArrayList<>();
            Pattern pattern = Pattern.compile("问题\\d+：");
            Matcher matcher = pattern.matcher(context);
            if (matcher.find()) {
                int lastEnd = 0;
                while (matcher.find()) {
                    if (lastEnd != matcher.start()) {
                        // Content before the previous match
                        questionList.add(context.substring(lastEnd, matcher.start()).trim());
                    }
                    lastEnd = matcher.end();
                }
                // Content after the last match
                if (lastEnd < context.length()) {
                    questionList.add(context.substring(lastEnd).trim());
                }

                int invalidNum = 0;
                if (!CollectionUtils.isEmpty(questionList)) {
                    conversationRecord.setQuestionType(QuestionTypeEnum.STEP.getCode());
                    conversationRecord.setQuestionNum(questionList.size());
                    for (String s : questionList) {
                        // Text after the answer label; count when marked as not applicable
                        String answer = s.substring(s.indexOf("回答：") + 3);
                        if (answer.contains("不涉及")) {
                            invalidNum++;
                        }
                    }
                }
            }
            conversationRecord.setContent(context);
            conversationRecord.setSessionType(sessionType);
            conversationRecordMapper.insert(conversationRecord);
        } catch (Exception e) {
            log.error("Failed to record user prompt, error:{}; sessionId:{},context:{},questionType:{},sessionType:{}"
                    , e.getMessage(), sessionId, context, questionType, sessionType, e);
        }

    }

    /**
     * Builds the system prompt.
     */
    private String buildSystemPrompt() {
        String toolDescriptions = toolRegistry.getAllToolDescriptions();

        return """
                你是专业的“需求任务影响范围分析”助手，通过工具调用理解代码并解答用户的问题。
                ## 当用户需要“需求影响范围分析”时，需要输出如下信息:
                1.**需求范围分析**：
                1）分析需求描述对代码的影响范围，包括但不限于：需求可能涉及哪些类、方法、字段、文件、数据库表等，以及它们之间的调用关系。
                2）查询可能与需求描述相关的代码内容和可能受需求业务逻辑影响的业务内容，将需求内容进行补充描述。
                2.变更代码分析：
                1）当用户提供commitid信息时，需获取变更代码，解析变更代码的变更情况
                2）当代码变更的业务逻辑描述不清晰时需要多结合原代码库的内容分析变更代码.特别注意要以文字的形式去解释代码变更内容
                3）若变更代码为java语言，可适当找出变更函数符合需求的调用链、url、以及url对应的按钮来辅助分析代码变更
                4）若变更代码为vue或者js语言，可尽量找出对应的页面或触发过程
                5）注意：要对每个commitid信息进行解析，可能存在与整体需求不一致的代码提交信息。这种“代码夹带”也要解析出对应的变更与测试点
                3.影响范围分析结果需满足以下要求：
                1）无代码内容、接口内容、字段内容等专业词汇的输出，请统一转换为中文描述
                2）影响范围描述应完整，减少模糊性语义或者词汇的表达
                3）最后总结一下上述影响范围分析结果涉及到的测试点：无需输出性能、安全、兼容测试点。
                4）注意功能测试点和业务测试点的挖掘
                
                
                ## 工具调用格式
                [TOOL_CALL:工具名:参数]
                参数格式具体见各工具说明，可以是单值或多值组合

                ## 可用工具
                """ + toolDescriptions + """

                ## 工具使用策略

                ### 核心原则
                1. **组合使用工具**：先用模糊匹配工具获取全局视图，再用细粒度工具深入细节
                2. **深入分析**：详细要求见下方"深入分析要求"部分

                ### 执行约束
                - 同一分析阶段的多个相关工具调用应批量进行（但是单次调用工具不能超过5个）
                - 有明确依赖关系的工具调用应顺序进行
                - 参考工具的"场景"和"能力"说明选择合适的工具
                - 查看工具的"示例"了解正确的调用格式
                - 使用 VIEW_METHOD 查看方法体时，格式为 VIEW_METHOD:ClassName.methodName 或 VIEW_METHOD:ClassName.methodName:深度（1-10）
                - 使用搜素代码等相关工具时，应确认参数是否为全路径，若是应修改为“全路径”为“文件名”再进行查询
                  比如：全路径: `src/main/java/com/aist/callback/AnalyzeCallback.java`,应修改为:'AnalyzeCallback.java'
                
                ### 工具调用失败处理
                - 当工具返回空结果时，考虑使用其他相关工具继续搜索
                - VIEW_METHOD未找到 → 尝试SEARCH_FILE全文搜索
                - 所有工具都无结果 → 明确告知用户"未找到相关代码"
                - 工具返回结果过大 → 通过更精确的搜索词或添加限制条件缩小范围

                ### 深入分析要求（必须遵守）

                **核心原则**：在得出任何结论前，必须通过 VIEW_METHOD 查看涉及的方法的实际实现，不得依赖方法名或调用关系做推断。

                **执行要求**：
                - 存在多条调用路径时：查看与问题相关的各路径中间方法体，对比各路径在子调用、传参、分支走向上的差异，排除与问题无关的路径
                - 方法体含条件分支（if/else、switch 等）时：逐一确认每个分支的触发条件和执行结果，不得假设分支走向
                - 被查看的方法内部调用了可能影响执行结果的其他方法时：继续用 VIEW_METHOD 跟进查看

                **停止标准**：基于已查看的实际代码，能有据可查地回答用户问题时即可停止。

                ## 真实性约束（必须严格遵守）

                以下是绝对禁止的行为，违反将导致不可接受的错误结果：

                1. **禁止编造**
                   - 不得编造任何代码片段、类名、方法名、表名、字段名、文件路径
                   - 不得基于经验或常识推测代码内容
                   - 不得假设某个类或方法存在，必须通过工具验证

                2. **禁止修改**
                   - 不得修改工具返回的代码内容
                   - 不得补充工具未返回的代码细节
                   - 不得"优化"或"改进"实际代码

                3. **必须基于证据**
                   - 所有回答必须且只能基于工具返回的实际结果
                   - 引用代码时必须标注完整文件路径
                   - 不确定的信息必须标注"需要进一步确认"
                   - 所有给出的接口url、类名、方法名、字段名等信息都必须真实且完整。

                4. **必须承认不足**
                   - 工具未返回相关信息时，必须明确说明"未找到相关代码/数据"
                   - VIEW_METHOD 未找到时，不要假设代码不存在（可能是配置文件，尝试 SEARCH_CONFIG）
                   - 可以建议使用其他工具（如 SEARCH_CONFIG）继续搜索

                5. **数据库约束**
                   - 数据库表结构和字段只能引用 DATABASE 工具返回的实际结果
                   - 不得假设任何表或字段存在

                6. **代码引用约束**
                   - 方法签名、参数、返回值只能引用搜索结果
                   - 业务逻辑只能基于实际看到的代码推理

                ## 回答格式
                - 使用 Markdown 格式
                - 包含：代码位置（文件路径）、业务含义、类之间关系
                - 代码块格式：严格标注代码语言（如：java/vue等）和文件路径
                - 明确区分"已确认"和"推测"的信息
                """;
    }

    private String buildUserPrompt(CodeAnalyzeContextDTO context) {
        StringBuilder sb = new StringBuilder();
        String question = context.getQuestion();

        sb.append("## 用户问题\n").append(question).append("\n\n");
        sb.append("请基于本轮问题进行分析。如果需要查看更多代码或数据，请使用工具请求；不要复用与当前问题无关的历史结论。");

        return sb.toString();
    }

    /**
     * Parse tool call directives from the model response.
     */
    private List<ToolRequest> parseToolRequests(String response) {
        List<ToolRequest> requests = new ArrayList<>();

        Matcher matcher = TOOL_CALL_PATTERN.matcher(response);
        while (matcher.find()) {
            String toolName = matcher.group(1).toUpperCase();
            String argsStr = matcher.group(2);

            List<String> arguments = new ArrayList<>();
            if (argsStr != null && !argsStr.trim().isEmpty()) {
                // Split on ':' as in tool contract (may re-split substrings later per tool)
                for (String arg : argsStr.split(":")) {
                    String trimmed = arg.trim();
                    if (!trimmed.isEmpty()) {
                        arguments.add(trimmed);
                    }
                }
            }

            ToolRequest req = new ToolRequest(toolName, arguments);
            requests.add(req);

            if (requests.size() >= 5) {
                break;
            }
        }

        return requests;
    }

    /**
     * Strip tool-call and clarification markers; keep only user-visible reasoning text.
     */
    private String filterToolCalls(String response) {
        String filtered = TOOL_CALL_PATTERN.matcher(response).replaceAll("");

        int askQuestionStart = filtered.indexOf("[ASK_QUESTION:");
        if (askQuestionStart != -1) {
            String questionJson = extractQuestionJson(filtered);
            if (questionJson != null) {
                String toRemove = "[ASK_QUESTION:" + questionJson + "]";
                filtered = filtered.replace(toRemove, "");
            }
        }

        filtered = filtered.replaceAll("(?s)<think>.*?</think>", "");
        filtered = filtered.replaceAll("(?s)\\*\\*思考过程\\*\\*.*?(?=\\n\\n|$)", "");
        return filtered.trim();
    }

    /**
     * Extract the JSON array inside [ASK_QUESTION:...] (handles nested brackets in strings).
     */
    private String extractQuestionJson(String response) {
        int startIndex = response.indexOf("[ASK_QUESTION:");
        if (startIndex == -1) {
            return null;
        }

        int jsonStart = startIndex + "[ASK_QUESTION:".length();

        // Bracket match for the JSON array
        int bracketCount = 0;
        int jsonEnd = -1;
        boolean inString = false;
        boolean escapeNext = false;

        for (int i = jsonStart; i < response.length(); i++) {
            char c = response.charAt(i);

            if (escapeNext) {
                escapeNext = false;
                continue;
            }

            if (c == '\\') {
                escapeNext = true;
                continue;
            }

            if (c == '"') {
                inString = !inString;
                continue;
            }

            if (!inString) {
                if (c == '[') {
                    bracketCount++;
                } else if (c == ']') {
                    bracketCount--;
                    if (bracketCount == 0) {
                        jsonEnd = i + 1;
                        break;
                    }
                }
            }
        }

        if (jsonEnd == -1) {
            log.warn("Could not find a complete question JSON array");
            return null;
        }

        return response.substring(jsonStart, jsonEnd);
    }

    /**
     * Validate clarification JSON; fill missing fields and required radio options.
     */
    private String validateAndFixQuestionJson(String questionJson) throws Exception {
        try {
            com.fasterxml.jackson.databind.JsonNode jsonNode = objectMapper.readTree(questionJson);

            if (!jsonNode.isArray()) {
                throw new Exception("Clarification payload must be a JSON array");
            }

            for (com.fasterxml.jackson.databind.JsonNode question : jsonNode) {
                if (!question.has("id") || !question.has("question") ||
                    !question.has("description") || !question.has("option") ||
                    !question.has("default") || !question.has("type")) {

                    com.fasterxml.jackson.databind.node.ObjectNode obj = (com.fasterxml.jackson.databind.node.ObjectNode) question;

                    if (!obj.has("id")) {
                        obj.put("id", "q" + System.currentTimeMillis());
                    }
                    if (!obj.has("type")) {
                        obj.put("type", "radio");
                    }
                    if (!obj.has("default")) {
                        if (obj.has("option") && obj.get("option").isArray() && obj.get("option").size() > 0) {
                            obj.put("default", obj.get("option").get(0).asText());
                        } else {
                            obj.put("default", "");
                        }
                    }
                    if (!obj.has("description")) {
                        obj.put("description", "");
                    }
                }

                if (question.has("option") && question.get("option").isArray()) {
                    com.fasterxml.jackson.databind.node.ArrayNode options = (com.fasterxml.jackson.databind.node.ArrayNode) question.get("option");
                    boolean hasNotApplicable = false;
                    boolean hasOther = false;

                    for (com.fasterxml.jackson.databind.JsonNode option : options) {
                        String optionText = option.asText();
                        if ("不涉及".equals(optionText)) hasNotApplicable = true;
                        if ("其他".equals(optionText)) hasOther = true;
                    }

                    if (!hasNotApplicable) {
                        options.add("不涉及");
                    }
                    if (!hasOther) {
                        options.add("其他");
                    }
                }
            }

            return objectMapper.writeValueAsString(jsonNode);

        } catch (Exception e) {
            log.error("JSON parse failed, raw: {}", questionJson);
            throw e;
        }
    }

    /**
     * Trim long chat history, keeping the system message.
     */
    private void trimHistory(List<DeepSeekUtil.Message> messages) {
        while (messages.size() > MAX_HISTORY_SIZE) {
            if (!"system".equals(messages.get(1).role)) {
                messages.remove(1);
            } else {
                break;
            }
        }
    }

    /**
     * Compress oversized tool output by keeping head and tail.
     */
    private String compressToolResult(String result) {
        if (result == null || result.length() <= MAX_TOOL_RESULT_LENGTH) {
            return result;
        }

        int headLength = MAX_TOOL_RESULT_LENGTH * 2 / 3;
        int tailLength = MAX_TOOL_RESULT_LENGTH / 3;

        String head = result.substring(0, headLength);
        String tail = result.substring(result.length() - tailLength);

        int omittedLength = result.length() - headLength - tailLength;
        String omittedInfo = String.format("\n\n... [已省略 %d 字符的中间内容] ...\n\n", omittedLength);

        return head + omittedInfo + tail;
    }
}
