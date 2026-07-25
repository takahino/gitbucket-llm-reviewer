package io.github.takahino.llmreviewer.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** HttpExchangeのJSON入出力・簡易ルーティング補助をまとめた共通ユーティリティ。 */
final class JsonHttp {

    static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private JsonHttp() {
    }

    static <T> T readJson(HttpExchange exchange, Class<T> type) throws IOException {
        return MAPPER.readValue(exchange.getRequestBody(), type);
    }

    static void writeJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = MAPPER.writeValueAsBytes(body);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    static void sendError(HttpExchange exchange, int status, String message) throws IOException {
        writeJson(exchange, status, Map.of("error", message));
    }

    /**
     * 状態変更・外部通信を伴うエンドポイントへのクロスオリジンリクエストを弾く。
     * Originヘッダーが無いリクエスト(同一オリジンのナビゲーションやcurl等のツール)は許可する。
     */
    static boolean isOriginAllowed(HttpExchange exchange, int port) {
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin == null) {
            return true;
        }
        return origin.equals("http://127.0.0.1:" + port) || origin.equals("http://localhost:" + port);
    }

    static Map<String, String> parseQuery(String query) {
        Map<String, String> result = new LinkedHashMap<>();
        if (query == null || query.isBlank()) {
            return result;
        }
        for (String pair : query.split("&")) {
            int idx = pair.indexOf('=');
            String key = idx >= 0 ? pair.substring(0, idx) : pair;
            String value = idx >= 0 ? pair.substring(idx + 1) : "";
            result.put(URLDecoder.decode(key, StandardCharsets.UTF_8), URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return result;
    }
}
