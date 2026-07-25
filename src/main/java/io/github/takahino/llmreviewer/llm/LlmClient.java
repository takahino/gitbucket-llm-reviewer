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
import java.util.logging.Level;
import java.util.logging.Logger;

/** OpenAI 互換 /chat/completions を呼び出すクライアント(Ollama/LM Studio/vLLM 等を想定)。 */
public class LlmClient {

    private static final Logger LOGGER = Logger.getLogger(LlmClient.class.getName());

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

    /** 通信断・タイムアウト・5xxなど一時的なエラーを表す。chat()内でのみ捕捉しリトライ判定に使う。 */
    private static final class RetryableLlmException extends RuntimeException {
        RetryableLlmException(String message, Throwable cause) {
            super(message, cause);
        }

        RetryableLlmException(String message) {
            super(message);
        }
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
        HttpRequest request = builder.build();

        int maxAttempts = config.retryMaxAttempts();
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return sendAndParse(request);
            } catch (RetryableLlmException e) {
                if (attempt >= maxAttempts) {
                    throw new LlmClientException(e.getMessage(), e.getCause());
                }
                long delayMs = config.retryBackoffMs() * (1L << (attempt - 1));
                LOGGER.log(Level.WARNING, "LLM呼び出しに失敗しました。%dms後にリトライします(試行 %d/%d): %s"
                        .formatted(delayMs, attempt, maxAttempts, e.getMessage()));
                sleep(delayMs);
            }
        }
        // maxAttempts >= 1 である限りループ内でreturnまたはthrowされるため到達しない
        throw new LlmClientException("LLM呼び出しに失敗しました(リトライ上限到達)");
    }

    private String sendAndParse(HttpRequest request) {
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new RetryableLlmException("LLMサーバーへの通信に失敗しました: " + config.baseUrl(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmClientException("LLMサーバーへの通信が割り込まれました", e);
        }

        int status = response.statusCode();
        if (status >= 500) {
            throw new RetryableLlmException("LLMサーバーが一時的なエラーを返しました(status=%d): %s"
                    .formatted(status, snippet(response.body())));
        }
        if (status < 200 || status >= 300) {
            throw new LlmClientException("LLMサーバーがエラーを返しました(status=%d): %s"
                    .formatted(status, snippet(response.body())));
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

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmClientException("リトライ待機中に割り込まれました", e);
        }
    }

    private static String snippet(String body) {
        String text = body == null ? "" : body;
        return text.substring(0, Math.min(500, text.length()));
    }
}
