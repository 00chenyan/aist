package com.aist.util;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * DeepSeek API 工具类
 * 支持与 DeepSeek AI 模型进行对话交互
 * API 文档: <a href="https://api-docs.deepseek.com/zh-cn/">...</a>
 */
@Slf4j
@Component
@AllArgsConstructor
public class DeepSeekUtil {

    private final static String DEFAULT_MODEL = "deepseek-reasoner";
    //private final static String DEFAULT_MODEL = "deepseek-reasoner";
    private static final String BASE_URL = "https://api.deepseek.com";
    private static final int CONNECT_TIMEOUT = 30;
    private static final int READ_TIMEOUT = 600;
    private static final int WRITE_TIMEOUT = 300;
    /**
     * 确定性输出的固定种子值
     * 使用固定种子配合 temperature=0 可以提高输出的可复现性
     */
    private static final int DETERMINISTIC_SEED = 42;

    @Value("${deepseek.api.key:}")
    private String deepseekApiKey;

    private OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT, TimeUnit.SECONDS)
            .build();


    private static final ObjectMapper objectMapper;

    static {
        objectMapper = new ObjectMapper();
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        objectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }


    public DeepSeekUtil(String deepseekApiKey) {
        this.deepseekApiKey = deepseekApiKey;
    }

    public DeepSeekUtil() {
    }


    /**
     * 创建 JSON 请求体
     */
    private RequestBody createJsonRequestBody(Object obj) throws IOException {
        String json = objectMapper.writeValueAsString(obj);
        return RequestBody.create(json, MediaType.parse("application/json; charset=utf-8"));
    }

    /**
     * 发送简单对话请求（非流式）
     *
     * @param userMessage 用户消息
     * @return AI 回复内容
     */
    public String chat(String userMessage) throws IOException {
        List<Message> messages = new ArrayList<>();
        messages.add(new Message("user", userMessage));
        return chat(messages, DEFAULT_MODEL);
    }

    /**
     * 发送多轮对话请求（非流式）
     *
     * @param messages 消息列表
     * @return AI 回复内容
     */
    public String chat(List<Message> messages) throws IOException {
        return chat(messages, DEFAULT_MODEL);
    }

    /**
     * 发送多轮对话请求（非流式）
     *
     * @param messages 消息列表
     * @param model    模型名称
     * @return AI 回复内容
     */
    public String chat(List<Message> messages, String model) throws IOException {
        ChatRequest request = new ChatRequest(model, messages, false);
        request.temperature = 0.0;
        request.seed = DETERMINISTIC_SEED;  // 设置固定种子增强可复现性
        ChatResponse response = chatCompletion(request);
        return response.choices.get(0).message.content;
    }


    /**
     * 执行对话完成请求（非流式）
     *
     * @param request 请求对象
     * @return 响应对象
     */
    public ChatResponse chatCompletion(ChatRequest request) throws IOException {
        Request httpRequest = new Request.Builder()
                .url(BASE_URL + "/chat/completions")
                .header("Authorization", "Bearer " + deepseekApiKey)
                .header("Content-Type", "application/json")
                .post(createJsonRequestBody(request))
                .build();

        try (Response response = httpClient.newCall(httpRequest).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "No response body";
                throw new IOException("HTTP error: " + response.code() + " - " + response.message() + " - " + errorBody);
            }

            assert response.body() != null;
            String responseBody = response.body().string();
            return objectMapper.readValue(responseBody, ChatResponse.class);
        }
    }

    /**
     * 消息对象
     */
    public static class Message {
        public String role;    // system, user, assistant
        public String content;

        public Message() {
        }

        public Message(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }

    /**
     * 对话请求对象
     */
    public static class ChatRequest {
        public String model;
        public List<Message> messages;
        public Boolean stream;
        public Double temperature;
        public Integer seed;
        @JsonProperty("response_format")
        public ChatType responseFormat;

        public ChatRequest(String model, List<Message> messages, Boolean stream) {
            this.model = model;
            this.messages = messages;
            this.stream = stream;
            this.responseFormat = new ChatType("text");
        }

    }

    @Data
    public static class ChatType {
        public String type;

        public ChatType(String type) {
            this.type = type;
        }
    }

    /**
     * 对话响应对象
     */
    public static class ChatResponse {
        public String id;
        public String object;
        public String model;
        public List<Choice> choices;
    }

    /**
     * 选择对象
     */
    public static class Choice {
        public Integer index;
        public Message message;
    }

}
