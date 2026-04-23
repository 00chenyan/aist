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
 * DeepSeek API utility.
 * Supports conversational interaction with DeepSeek models.
 * API documentation: <a href="https://api-docs.deepseek.com/">https://api-docs.deepseek.com/</a>
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
     * Fixed seed for more deterministic output.
     * A fixed seed with temperature=0 improves reproducibility.
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
     * Builds a JSON request body.
     */
    private RequestBody createJsonRequestBody(Object obj) throws IOException {
        String json = objectMapper.writeValueAsString(obj);
        return RequestBody.create(json, MediaType.parse("application/json; charset=utf-8"));
    }

    /**
     * Sends a simple chat request (non-streaming).
     *
     * @param userMessage user message
     * @return assistant reply text
     */
    public String chat(String userMessage) throws IOException {
        List<Message> messages = new ArrayList<>();
        messages.add(new Message("user", userMessage));
        return chat(messages, DEFAULT_MODEL);
    }

    /**
     * Sends a multi-turn chat request (non-streaming).
     *
     * @param messages message list
     * @return assistant reply text
     */
    public String chat(List<Message> messages) throws IOException {
        return chat(messages, DEFAULT_MODEL);
    }

    /**
     * Sends a multi-turn chat request (non-streaming).
     *
     * @param messages message list
     * @param model    model name
     * @return assistant reply text
     */
    public String chat(List<Message> messages, String model) throws IOException {
        ChatRequest request = new ChatRequest(model, messages, false);
        request.temperature = 0.0;
        request.seed = DETERMINISTIC_SEED;  // fixed seed for better reproducibility
        ChatResponse response = chatCompletion(request);
        return response.choices.get(0).message.content;
    }


    /**
     * Performs a chat completion request (non-streaming).
     *
     * @param request request payload
     * @return response object
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
     * Chat message.
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
     * Chat completion request payload.
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
     * Chat completion response.
     */
    public static class ChatResponse {
        public String id;
        public String object;
        public String model;
        public List<Choice> choices;
    }

    /**
     * Choice entry in the response.
     */
    public static class Choice {
        public Integer index;
        public Message message;
    }

}
