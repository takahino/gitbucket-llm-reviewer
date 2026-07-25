package io.github.takahino.llmreviewer.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import io.github.takahino.llmreviewer.llm.ModelListClient;

import java.io.IOException;

/**
 * POST /api/embedding/models — rag.embeddingModel用の補助エンドポイント。
 * providerがollamaかopenai-compatibleかでモデル一覧取得先のAPI形式が異なるため分岐する。
 */
final class EmbeddingModelsApiHandler implements HttpHandler {

    private final ModelListClient modelListClient;
    private final int port;

    EmbeddingModelsApiHandler(ModelListClient modelListClient, int port) {
        this.modelListClient = modelListClient;
        this.port = port;
    }

    record RequestBody(String baseUrl, String provider, String apiKey) {
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
            ModelListClient.Result result = "openai-compatible".equals(body.provider())
                    ? modelListClient.listOpenAiCompatible(body.baseUrl(), body.apiKey())
                    : modelListClient.listOllama(body.baseUrl());
            JsonHttp.writeJson(exchange, 200, result);
        } finally {
            exchange.close();
        }
    }
}
