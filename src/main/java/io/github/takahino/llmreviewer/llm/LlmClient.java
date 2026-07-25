package io.github.takahino.llmreviewer.llm;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import io.github.takahino.llmreviewer.config.AppConfig;

import java.time.Duration;
import java.util.List;

/**
 * OpenAI 互換 /chat/completions を呼び出すクライアント(Ollama/LM Studio/vLLM 等を想定)。
 * langchain4j の {@link OpenAiChatModel} に委譲する。5xx/408/429 は
 * {@code RetriableException} としてリトライ対象、4xx(400/401/403/404 等)は
 * {@code NonRetriableException} として即座に失敗する(langchain4j内蔵の
 * {@code RetryUtils}/{@code ExceptionMapper} による挙動で、従来の自前リトライ制御と同等)。
 */
public class LlmClient {

    private final ChatModel chatModel;

    public LlmClient(AppConfig.LlmConfig config) {
        this.chatModel = OpenAiChatModel.builder()
                .baseUrl(config.baseUrl())
                .apiKey(config.apiKey().isBlank() ? "unused" : config.apiKey())
                .modelName(config.model())
                .temperature(config.temperature())
                .maxTokens(config.maxTokens())
                .timeout(Duration.ofSeconds(config.timeoutSeconds()))
                // config.retryMaxAttempts()は初回試行を含む最大試行回数。langchain4jのmaxRetriesは
                // 初回とは別の追加リトライ回数を指すため、-1して意味を合わせる(最小0)。
                .maxRetries(Math.max(0, config.retryMaxAttempts() - 1))
                .responseFormat(ResponseFormat.builder().type(ResponseFormatType.JSON).build())
                .build();
    }

    public String chat(List<ChatMessage> messages) {
        ChatRequest request = ChatRequest.builder().messages(messages).build();
        ChatResponse response = chatModel.chat(request);
        return response.aiMessage().text();
    }
}
