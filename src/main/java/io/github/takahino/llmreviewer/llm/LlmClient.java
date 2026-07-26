package io.github.takahino.llmreviewer.llm;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.json.JsonRawSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;
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

    /**
     * ReviewOutput(status/requestedFiles/summary/findings)とMentionReplyOutput(status/requestedFiles/answer)の
     * 両方の形状を許容する共有スキーマ(responseFormat=json_schema時に使用)。LlmClientはReviewOrchestratorと
     * MentionReplyOrchestratorの両方から共有される単一インスタンスのため、片方でしか使わないフィールドが
     * 混在する。
     *
     * 型制約なし({})でnull許容を表現すると、一部のllama.cpp系ローカルサーバー(LM Studio等)の
     * grammar変換が「制約なし」を適切に扱えず、summaryのような長いMarkdown文字列を書く際に
     * カンマ抜け・波括弧混入等の壊れたJSONを生成することを実機検証で確認した。そのため全プロパティに
     * 具体的な型(string/integer/array/object)を必ず指定し、null許容が必要なプロパティ(summary/answer/
     * findings[].line)は "required" に含めず省略可能にすることでnull相当を表現する
     * (union type配列 "type": ["string","null"] も使わない。より広く実績のある基本語彙のみで構成する)。
     * 両record共に @JsonIgnoreProperties(ignoreUnknown = true) のため、不要なフィールドが
     * 含まれてもパースは壊れない。
     */
    private static final String SHARED_OUTPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "status": { "type": "string" },
                "requestedFiles": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "properties": {
                      "path": { "type": "string" },
                      "reason": { "type": "string" }
                    },
                    "required": ["path", "reason"]
                  }
                },
                "summary": { "type": "string" },
                "answer": { "type": "string" },
                "findings": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "properties": {
                      "file": { "type": "string" },
                      "line": { "type": "integer" },
                      "severity": { "type": "string" },
                      "perspective": { "type": "string" },
                      "comment": { "type": "string" }
                    },
                    "required": ["file", "severity", "perspective", "comment"]
                  }
                }
              },
              "required": ["status"]
            }
            """;

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
                .responseFormat(resolveResponseFormat(config.responseFormat()))
                .build();
    }

    /**
     * OpenAI互換サーバーによってresponse_format.typeに許容する値が異なる
     * (例: LM StudioはjsonObjectを拒否しjson_schema/textのみ許可)ため、config.ymlのllm.responseFormatで切り替える。
     */
    private static ResponseFormat resolveResponseFormat(String responseFormat) {
        return switch (responseFormat) {
            case "text" -> ResponseFormat.builder().type(ResponseFormatType.TEXT).build();
            case "json_schema" -> ResponseFormat.builder()
                    .type(ResponseFormatType.JSON)
                    .jsonSchema(JsonSchema.builder()
                            .name("llm_reviewer_output")
                            .rootElement(JsonRawSchema.from(SHARED_OUTPUT_SCHEMA))
                            .build())
                    .build();
            default -> ResponseFormat.builder().type(ResponseFormatType.JSON).build(); // "json_object"
        };
    }

    public String chat(List<ChatMessage> messages) {
        ChatRequest request = ChatRequest.builder().messages(messages).build();
        ChatResponse response = chatModel.chat(request);
        return response.aiMessage().text();
    }
}
