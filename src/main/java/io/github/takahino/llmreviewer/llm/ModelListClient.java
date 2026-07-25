package io.github.takahino.llmreviewer.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 管理UIの「モデル一覧をプルダウン表示」を補助するための薄いクライアント。
 * langchain4jのLlmClientはchat専用のためここでは使わず、直接HTTPで叩く。
 * 接続失敗はUXフォールバック(自由入力に戻す)の一部として扱うため、例外を投げず {@link Result} で返す。
 */
public final class ModelListClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(8);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public record Result(boolean ok, List<String> models, String error) {
        public static Result ok(List<String> models) {
            return new Result(true, models, null);
        }

        public static Result failure(String error) {
            return new Result(false, List.of(), error);
        }
    }

    /** OpenAI互換(Ollama/LM Studio/vLLM等) GET {baseUrl}/models -&gt; {"data":[{"id":"..."}]} */
    public Result listOpenAiCompatible(String baseUrl, String apiKey) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(trimTrailingSlash(baseUrl) + "/models"))
                .timeout(TIMEOUT)
                .GET();
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
        return fetchAndParse(builder.build(), "data", "id");
    }

    /** Ollamaネイティブ GET {baseUrl}/api/tags -&gt; {"models":[{"name":"..."}]} */
    public Result listOllama(String baseUrl) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(trimTrailingSlash(baseUrl) + "/api/tags"))
                .timeout(TIMEOUT)
                .GET()
                .build();
        return fetchAndParse(request, "models", "name");
    }

    private Result fetchAndParse(HttpRequest request, String listField, String nameField) {
        try {
            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                return Result.failure("HTTP " + response.statusCode());
            }
            JsonNode list = MAPPER.readTree(response.body()).path(listField);
            List<String> names = new ArrayList<>();
            list.forEach(node -> {
                if (node.hasNonNull(nameField)) {
                    names.add(node.get(nameField).asText());
                }
            });
            return Result.ok(names);
        } catch (IOException e) {
            return Result.failure(e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.failure(e.getMessage());
        }
    }

    private static String trimTrailingSlash(String baseUrl) {
        return baseUrl.replaceAll("/+$", "");
    }
}
