package io.github.takahino.llmreviewer.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import io.github.takahino.llmreviewer.llm.ModelListClient;

import java.io.IOException;

/**
 * POST /api/llm/models・POST /api/embedding/models 共通のハンドラ。
 * config編集画面でbaseUrl入力後に呼ばれ、接続先からモデル一覧を取得できればプルダウン化するための補助エンドポイント。
 * apiKeyをログ・URLに残さないようPOSTにしている。取得方法(OpenAI互換固定/provider分岐)は呼び出し側で指定する。
 */
final class ModelListApiHandler implements HttpHandler {

    /** llm.model用はOpenAI互換固定、rag.embeddingModel用はprovider(ollama/openai-compatible)で分岐する。 */
    @FunctionalInterface
    interface ModelResolver {
        ModelListClient.Result resolve(ModelListClient client, RequestBody body);
    }

    record RequestBody(String baseUrl, String provider, String apiKey) {
    }

    private final ModelListClient modelListClient;
    private final ModelResolver resolver;
    private final int port;

    ModelListApiHandler(ModelListClient modelListClient, int port, ModelResolver resolver) {
        this.modelListClient = modelListClient;
        this.port = port;
        this.resolver = resolver;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            if (!"POST".equals(exchange.getRequestMethod())) {
                JsonHttp.sendError(exchange, 405, "許可されていないメソッドです");
                return;
            }
            if (!JsonHttp.isOriginAllowed(exchange, port)) {
                JsonHttp.sendError(exchange, 403, "許可されていないOriginからのリクエストです");
                return;
            }
            RequestBody body;
            try {
                body = JsonHttp.readJson(exchange, RequestBody.class);
            } catch (JsonProcessingException e) {
                JsonHttp.sendError(exchange, 400, "リクエストの解析に失敗しました: " + e.getMessage());
                return;
            }
            if (body.baseUrl() == null || body.baseUrl().isBlank()) {
                JsonHttp.sendError(exchange, 400, "baseUrl は必須です");
                return;
            }
            JsonHttp.writeJson(exchange, 200, resolver.resolve(modelListClient, body));
        } finally {
            exchange.close();
        }
    }
}
