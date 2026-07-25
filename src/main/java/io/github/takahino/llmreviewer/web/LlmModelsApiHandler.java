package io.github.takahino.llmreviewer.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import io.github.takahino.llmreviewer.llm.ModelListClient;

import java.io.IOException;

/**
 * POST /api/llm/models — config編集画面で llm.baseUrl 入力後に呼ばれ、OpenAI互換エンドポイントから
 * モデル一覧を取得できればプルダウン化するための補助エンドポイント。apiKeyをログ・URLに残さないようPOSTにしている。
 */
final class LlmModelsApiHandler implements HttpHandler {

    private final ModelListClient modelListClient;
    private final int port;

    LlmModelsApiHandler(ModelListClient modelListClient, int port) {
        this.modelListClient = modelListClient;
        this.port = port;
    }

    record RequestBody(String baseUrl, String apiKey) {
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
            ModelListClient.Result result = modelListClient.listOpenAiCompatible(body.baseUrl(), body.apiKey());
            JsonHttp.writeJson(exchange, 200, result);
        } finally {
            exchange.close();
        }
    }
}
