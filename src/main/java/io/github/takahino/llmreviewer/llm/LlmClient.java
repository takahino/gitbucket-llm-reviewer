package io.github.takahino.llmreviewer.llm;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.takahino.llmreviewer.config.AppConfig;
import io.github.takahino.llmreviewer.llm.model.ChatCompletionRequest;
import io.github.takahino.llmreviewer.llm.model.ChatCompletionResponse;
import io.github.takahino.llmreviewer.llm.model.ChatMessage;
import io.github.takahino.llmreviewer.llm.model.ResponseFormat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/** OpenAI 互換 /chat/completions を呼び出すクライアント(Ollama/LM Studio/vLLM 等を想定)。 */
public class LlmClient {

    private final JsonMapper jsonMapper = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .build();

    private final HttpClient httpClient;
    private final AppConfig.LlmConfig config;

    public LlmClient(AppConfig.LlmConfig config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public String chat(List<ChatMessage> messages) {
        ChatCompletionRequest requestBody = new ChatCompletionRequest(
                config.model(), messages, config.temperature(), config.maxTokens(), new ResponseFormat("json_object"));
        String requestJson;
        try {
            requestJson = jsonMapper.writeValueAsString(requestBody);
        } catch (IOException e) {
            throw new LlmClientException("LLMリクエストのJSON化に失敗しました", e);
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(config.baseUrl() + "/chat/completions"))
                .timeout(Duration.ofSeconds(config.timeoutSeconds()))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(requestJson, StandardCharsets.UTF_8));
        if (!config.apiKey().isBlank()) {
            builder.header("Authorization", "Bearer " + config.apiKey());
        }

        HttpResponse<String> response;
        try {
            response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new LlmClientException("LLMサーバーへの通信に失敗しました: " + config.baseUrl(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmClientException("LLMサーバーへの通信が割り込まれました", e);
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String body = response.body() == null ? "" : response.body();
            throw new LlmClientException("LLMサーバーがエラーを返しました(status=%d): %s"
                    .formatted(response.statusCode(), body.substring(0, Math.min(500, body.length()))));
        }

        try {
            ChatCompletionResponse parsed = jsonMapper.readValue(response.body(), ChatCompletionResponse.class);
            if (parsed.choices().isEmpty()) {
                throw new LlmClientException("LLM応答にchoicesが含まれていません: " + response.body());
            }
            return parsed.choices().get(0).message().content();
        } catch (IOException e) {
            throw new LlmClientException("LLM応答のパースに失敗しました: " + response.body(), e);
        }
    }
}
