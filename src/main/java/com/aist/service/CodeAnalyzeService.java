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
 * 代码分析服务
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

    // 常量定义
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
     * 会话存储 TODO 改成数据库持久化存储。
     * key: sessionId, value: 上下文实例
     * 每个用户有独立的sessionId(UUID)，不会互相影响
     */
    private final Map<String, CodeAnalyzeContextDTO> sessionContexts = new ConcurrentHashMap<>();

    /**
     * 会话最后访问时间（用于过期清理）
     */
    private final Map<String, Long> sessionLastAccess = new ConcurrentHashMap<>();

    /**
     * 会话过期时间：30分钟
     */
    private static final long SESSION_EXPIRE_MS = 30 * 60 * 1000;

    private void validateAnalyzeRequest(CodeAnalyzeRequest request) {
        if (request.getQuestion() == null || request.getQuestion().trim().isEmpty()) {
            throw new IllegalArgumentException("问题描述不能为空");
        }
        String projectPath = aistConfig.getCodeRepo().getPath();
        if (projectPath == null || projectPath.isEmpty()) {
            throw new IllegalStateException("未配置代码仓库路径");
        }
        if (!new File(projectPath).exists()) {
            throw new IllegalStateException("代码仓库路径不存在: " + projectPath);
        }
    }

    private void completeEmitter(SseEmitter emitter) {
        if (emitter == null) {
            return;
        }
        try {
            emitter.complete();
        } catch (Exception e) {
            log.debug("emitter.complete 忽略: {}", e.getMessage());
        }
    }

    /**
     * 非流式分析：一次请求返回完整结果（与 /stream 同源逻辑，无 SSE）
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
            log.warn("分析请求无效: {}", e.getMessage());
            result.put("success", false);
            result.put("eventType", CodeAnalyzeEvent.TYPE_ERROR);
            result.put("message", e.getMessage());
            return result;
        } catch (Exception e) {
            log.error("代码分析失败", e);
            result.put("success", false);
            result.put("eventType", CodeAnalyzeEvent.TYPE_ERROR);
            result.put("message", "分析失败: " + e.getMessage());
            return result;
        }
    }

    /**
     * 流式分析代码
     *
     * @param request 请求
     * @param emitter SSE发射器
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
            log.warn("分析请求无效: {}", e.getMessage());
            sendEventSafe(emitter, CodeAnalyzeEvent.error(e.getMessage()));
            completeEmitter(emitter);
        } catch (Exception e) {
            log.error("代码分析失败", e);
            try {
                sendEventSafe(emitter, CodeAnalyzeEvent.error("分析失败: " + e.getMessage()));
                completeEmitter(emitter);
            } catch (Exception ex) {
                log.error("发送错误事件失败", ex);
            }
        } finally {
            // 关闭临时会话的日志记录器（没有 sessionId 的一次性会话）
            if (context != null
                    && context.getRequest() != null
                    && (context.getRequest().getSessionId() == null
                        || context.getRequest().getSessionId().trim().isEmpty())
                    ) {
            }
        }
    }

    /**
     * 准备上下文并执行 LLM 分析（流式 / 非流式共用）
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
     * 创建分析上下文
     */
    private CodeAnalyzeContextDTO createContext(CodeAnalyzeRequest request, String projectPath, String projectName) {
        CodeAnalyzeContextDTO context = new CodeAnalyzeContextDTO();
        context.setRequest(request);
        context.setProjectName(projectName);
        context.setProjectPath(projectPath);
        context.setDeepseekApiKey(deepseekApiKey);
        context.setToolRegistry(toolRegistry);

        // 数据库配置（从 target-db 获取）
        context.setDatabaseName(aistConfig.getTargetDb().getDefaultDatabase());
        context.setDbSourceName("dev");  // 默认数据源名称

        // 初始化对话历史
        context.setConversationHistory(new ArrayList<>());

        // 初始化分析日志记录器
        String sessionId = request.getSessionId() != null ? request.getSessionId() : UUID.randomUUID().toString();
        try {
        } catch (Exception e) {
            log.warn("创建分析日志记录器失败: {}", e.getMessage());
        }

        return context;
    }

    /**
     * 创建 SSE 回调
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
     * 发送事件（安全版本，忽略异常）
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
     * 发送 SSE 事件
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
     * 清理会话
     */
    public void clearSession(String sessionId) {
        CodeAnalyzeContextDTO ctx = sessionContexts.remove(sessionId);
        sessionLastAccess.remove(sessionId);
        // 清理会话时关闭对应的日志记录器
    }

    /**
     * 清理过期会话（30分钟未访问的会话）
     */
    private void cleanExpiredSessions() {
        long now = System.currentTimeMillis();
        sessionLastAccess.entrySet().removeIf(entry -> {
            if (now - entry.getValue() > SESSION_EXPIRE_MS) {
                CodeAnalyzeContextDTO ctx = sessionContexts.remove(entry.getKey());
                log.info("清理过期会话: {}", entry.getKey());
                return true;
            }
            return false;
        });
    }



    // ==================== LLM 智能分析 ====================

    /**
     * 执行 LLM 智能分析（入口）
     * 若检测到影响范围分析请求且同时包含"需求内容"与"提交id"，则拆分为两路并行分析后合并；
     * 否则走普通单路分析流程。
     */
    private void executeLlmAnalysis(CodeAnalyzeContextDTO context) throws Exception {
        log.info("开始LLM智能分析");

        String question = context.getQuestion();

        // 记录用户问题
        conversationRecord(context.getRequest().getSessionId(), question,
                QuestionTypeEnum.QUESTION.getCode(), SessionTypeEnum.QUESTION.getCode());

        String finalAnswer;

        if (isImpactAnalysisRequest(question)) {
            String[] parts = extractImpactParts(question);
            if (parts != null) {
                // 拆分执行：parts[0]=前缀, parts[1]=需求内容, parts[2]=提交id
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

        // 记录最终分析结果
        String recorded = context.getFinalAnswer() != null ? context.getFinalAnswer() : "";
        conversationRecord(context.getRequest().getSessionId(), recorded,
                QuestionTypeEnum.DONE.getCode(), SessionTypeEnum.ANSWER.getCode());

        log.info("LLM智能分析完成");
    }

    /**
     * 判断是否为影响范围分析请求
     */
    private boolean isImpactAnalysisRequest(String question) {
        return question != null && question.contains("影响范围");
    }

    /**
     * 从问题中提取"需求内容"和"提交id"两个部分。
     * 若两者同时存在则返回 String[3]：{前缀, 需求内容, 提交id}；否则返回 null（不需要拆分）。
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

        // 前缀：两个关键字中先出现的那个之前的内容
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
     * 拆分执行影响范围分析：分别分析需求内容（A）和提交id（B），再调用 DeepSeek 综合两路结果。
     */
    private String executeSplitImpactAnalysis(CodeAnalyzeContextDTO context,
                                               String prefix, String needsContent, String commitId) throws Exception {
        String basePrefix = prefix.isEmpty() ? "" : prefix + "\n";

        // A 路：需求内容
        context.notifyStep("正在分析【需求内容】影响范围...");
        String questionA = basePrefix + "需求内容：" + needsContent;
        String resultA = runSubAnalysis(context, questionA, "【需求内容】");
        if (resultA == null) {
            resultA = "（需求内容分析未得到结果）";
        }

        // B 路：提交id
        context.notifyStep("正在分析【提交ID】影响范围...");
        String questionB = basePrefix + "提交id：" + commitId;
        String resultB = runSubAnalysis(context, questionB, "【提交ID】");
        if (resultB == null) {
            resultB = "（提交ID分析未得到结果）";
        }

        // 综合两路结果
        context.notifyStep("正在综合A、B版本分析结果...");
        return combineImpactResults(context, resultA, resultB);
    }

    /**
     * 基于原始上下文创建子上下文，替换问题后执行单路分析，返回最终答案文本。
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
     * 创建带标签前缀的回调，用于子分析过程中区分步骤归属。
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
     * 调用 DeepSeek 综合 A、B 两路影响范围分析结果，统计测试点异同。
     */
    private String combineImpactResults(CodeAnalyzeContextDTO context,
                                         String resultA, String resultB) throws Exception {
        DeepSeekUtil deepSeek = new DeepSeekUtil(context.getDeepseekApiKey());
        String systemPrompt = "请结合A和B版本的需求范围内容，输出一个包含A版和B版汇总的需求范围描述。并统及A、B版本含有测试点的异同数目";
        String userPrompt = "A版本影响范围分析结果：\n" + resultA + "\n\nB版本影响范围分析结果：\n" + resultB;

        List<DeepSeekUtil.Message> messages = new ArrayList<>();
        messages.add(new DeepSeekUtil.Message("system", systemPrompt));
        messages.add(new DeepSeekUtil.Message("user", userPrompt));
        log.info("合并的用户提示词{}",userPrompt);

        return deepSeek.chat(messages);
    }

    /**
     * 执行单路 LLM 分析循环，返回最终答案文本（不写入 DB，由调用方决定是否记录）。
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


            // 检查是否有问题澄清请求
            String questionJson = extractQuestionJson(response);
            if (questionJson != null) {
                log.info("检测到问题澄清请求: {}", questionJson);
                try {
                    questionJson = validateAndFixQuestionJson(questionJson);
                    log.info("验证后的问题 JSON: {}", questionJson);

                    context.setClarificationQuestionJson(questionJson);
                    if (context.isBlockingMode()) {
                        context.setConversationHistory(messages);
                        log.info("等待用户回答问题（非流式）");
                        return null;
                    }

                    SseEmitter emitter = (SseEmitter) context.getEmitter();
                    if (emitter != null) {
                        sendEventSafe(emitter, CodeAnalyzeEvent.question(questionJson));
                        sendEventSafe(emitter, CodeAnalyzeEvent.done(""));
                        completeEmitter(emitter);
                    }

                    context.setConversationHistory(messages);
                    log.info("等待用户回答问题");
                    return null;
                } catch (Exception e) {
                    log.error("问题 JSON 格式错误: {}", questionJson, e);
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

    /*
     * 提取用户提示词
     * @param sessionId 会话ID
     * @param context 上下文
     * @param questionType 问题类型
     * @param sessionType 会话类型
     */
    private void conversationRecord(String sessionId, String context, int questionType, int sessionType) {
        try {
            //记录用户提示词
            ConversationRecord conversationRecord = new ConversationRecord();
            conversationRecord.setSessionId(sessionId);
            conversationRecord.setQuestionType(questionType);

            //通过正则表达式判断question中是否包含“问题+数字+：”比如："问题1："，"问题2："，有则按照规则分割开形成一个list
            List<String> questionList = new ArrayList<>();
            Pattern pattern = Pattern.compile("问题\\d+：");
            Matcher matcher = pattern.matcher(context);
            if (matcher.find()) {
                int lastEnd = 0;
                while (matcher.find()) {
                    if (lastEnd != matcher.start()) {
                        // 添加上一个匹配项之前的内容
                        questionList.add(context.substring(lastEnd, matcher.start()).trim());
                    }
                    lastEnd = matcher.end();
                }
                // 添加最后一个匹配项之后的内容
                if (lastEnd < context.length()) {
                    questionList.add(context.substring(lastEnd).trim());
                }

                int invalidNum = 0;
                if (!CollectionUtils.isEmpty(questionList)) {
                    conversationRecord.setQuestionType(QuestionTypeEnum.STEP.getCode());
                    conversationRecord.setQuestionNum(questionList.size());
                    for (String s : questionList) {
                        //获取“回答：”后面的内容并判断是否为“不涉及”
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
            log.error("记录用户提示词失败,error:{}; sessionId:{},context:{},questionType:{},sessionType:{}"
                    , e.getMessage(), sessionId, context, questionType, sessionType, e);
        }

    }

    /**
     * 构建系统提示词
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

    /**
     * 构建用户提示词
     */
    private String buildUserPrompt(CodeAnalyzeContextDTO context) {
        StringBuilder sb = new StringBuilder();

        String question = context.getQuestion();

        sb.append("## 用户问题\n").append(question).append("\n\n");
        sb.append("请基于本轮问题进行分析。如果需要查看更多代码或数据，请使用工具请求；不要复用与当前问题无关的历史结论。");

        return sb.toString();
    }

    /**
     * 解析工具调用请求
     */
    private List<ToolRequest> parseToolRequests(String response) {
        List<ToolRequest> requests = new ArrayList<>();

        Matcher matcher = TOOL_CALL_PATTERN.matcher(response);
        while (matcher.find()) {
            String toolName = matcher.group(1).toUpperCase();
            String argsStr = matcher.group(2);

            List<String> arguments = new ArrayList<>();
            if (argsStr != null && !argsStr.trim().isEmpty()) {
                // 按冒号分割参数（与工具定义的格式保持一致）
                for (String arg : argsStr.split(":")) {
                    String trimmed = arg.trim();
                    if (!trimmed.isEmpty()) {
                        arguments.add(trimmed);
                    }
                }
            }

            ToolRequest req = new ToolRequest(toolName, arguments);
            requests.add(req);

            // 最多5个工具调用
            if (requests.size() >= 5) {
                break;
            }
        }

        return requests;
    }

    /**
     * 过滤工具调用标记和问题澄清标记
     */
    private String filterToolCalls(String response) {
        String filtered = TOOL_CALL_PATTERN.matcher(response).replaceAll("");

        // 过滤问题澄清标记（手动查找并移除）
        int askQuestionStart = filtered.indexOf("[ASK_QUESTION:");
        if (askQuestionStart != -1) {
            String questionJson = extractQuestionJson(filtered);
            if (questionJson != null) {
                // 移除整个 [ASK_QUESTION:...] 标记
                String toRemove = "[ASK_QUESTION:" + questionJson + "]";
                filtered = filtered.replace(toRemove, "");
            }
        }

        // 过滤思考过程
        filtered = filtered.replaceAll("(?s)<think>.*?</think>", "");
        filtered = filtered.replaceAll("(?s)\\*\\*思考过程\\*\\*.*?(?=\\n\\n|$)", "");
        return filtered.trim();
    }

    /**
     * 从响应中提取问题 JSON
     * 手动解析以正确处理嵌套的方括号
     */
    private String extractQuestionJson(String response) {
        int startIndex = response.indexOf("[ASK_QUESTION:");
        if (startIndex == -1) {
            return null;
        }

        // 跳过 "[ASK_QUESTION:" 部分
        int jsonStart = startIndex + "[ASK_QUESTION:".length();

        // 手动匹配 JSON 数组的边界
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
            log.warn("未找到完整的问题 JSON 数组");
            return null;
        }

        return response.substring(jsonStart, jsonEnd);
    }

    /**
     * 验证和修复问题 JSON 格式
     */
    private String validateAndFixQuestionJson(String questionJson) throws Exception {
        try {
            // 尝试解析 JSON
            com.fasterxml.jackson.databind.JsonNode jsonNode = objectMapper.readTree(questionJson);

            // 验证是否是数组
            if (!jsonNode.isArray()) {
                throw new Exception("问题 JSON 必须是数组格式");
            }

            // 验证每个问题对象的必需字段
            for (com.fasterxml.jackson.databind.JsonNode question : jsonNode) {
                if (!question.has("id") || !question.has("question") ||
                    !question.has("description") || !question.has("option") ||
                    !question.has("default") || !question.has("type")) {

                    // 尝试补充缺失的字段
                    com.fasterxml.jackson.databind.node.ObjectNode obj = (com.fasterxml.jackson.databind.node.ObjectNode) question;

                    if (!obj.has("id")) {
                        obj.put("id", "q" + System.currentTimeMillis());
                    }
                    if (!obj.has("type")) {
                        obj.put("type", "radio");
                    }
                    if (!obj.has("default")) {
                        // 如果有 option 数组，使用第一个选项作为默认值
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

                // 验证 option 数组包含"不涉及"和"其他"
                if (question.has("option") && question.get("option").isArray()) {
                    com.fasterxml.jackson.databind.node.ArrayNode options = (com.fasterxml.jackson.databind.node.ArrayNode) question.get("option");
                    boolean hasNotApplicable = false;
                    boolean hasOther = false;

                    for (com.fasterxml.jackson.databind.JsonNode option : options) {
                        String optionText = option.asText();
                        if ("不涉及".equals(optionText)) hasNotApplicable = true;
                        if ("其他".equals(optionText)) hasOther = true;
                    }

                    // 补充缺失的选项
                    if (!hasNotApplicable) {
                        options.add("不涉及");
                    }
                    if (!hasOther) {
                        options.add("其他");
                    }
                }
            }

            // 返回修复后的 JSON
            return objectMapper.writeValueAsString(jsonNode);

        } catch (Exception e) {
            log.error("JSON 解析失败，原始内容: {}", questionJson);
            throw e;
        }
    }

    /**
     * 裁剪对话历史
     */
    private void trimHistory(List<DeepSeekUtil.Message> messages) {
        while (messages.size() > MAX_HISTORY_SIZE) {
            // 保留系统消息，删除最早的用户/助手消息
            if (!"system".equals(messages.get(1).role)) {
                messages.remove(1);
            } else {
                break;
            }
        }
    }

    /**
     * 压缩工具结果
     * 如果结果过长，进行智能截断
     */
    private String compressToolResult(String result) {
        if (result == null || result.length() <= MAX_TOOL_RESULT_LENGTH) {
            return result;
        }

        // 保留开头和结尾，中间部分截断
        int headLength = MAX_TOOL_RESULT_LENGTH * 2 / 3;
        int tailLength = MAX_TOOL_RESULT_LENGTH / 3;

        String head = result.substring(0, headLength);
        String tail = result.substring(result.length() - tailLength);

        int omittedLength = result.length() - headLength - tailLength;
        String omittedInfo = String.format("\n\n... [已省略 %d 字符的中间内容] ...\n\n", omittedLength);

        return head + omittedInfo + tail;
    }
}
