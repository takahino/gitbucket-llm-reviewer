package io.github.takahino.llmreviewer.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import io.github.takahino.llmreviewer.config.AppConfig;
import io.github.takahino.llmreviewer.config.AppConfigWriter;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * GET /api/config でconfig.ymlの現在値を返し、PUT /api/config で全体を検証の上、原子的に書き換える。
 * バリデーションは {@link AppConfig} の各recordコンパクトコンストラクタ(IllegalArgumentException)をそのまま再利用する。
 */
final class ConfigApiHandler implements HttpHandler {

    private static final Logger LOGGER = Logger.getLogger(ConfigApiHandler.class.getName());

    private final Path configPath;
    private final AtomicReference<AppConfig> currentConfig;
    private final int port;

    ConfigApiHandler(Path configPath, AtomicReference<AppConfig> currentConfig, int port) {
        this.configPath = configPath;
        this.currentConfig = currentConfig;
        this.port = port;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            switch (exchange.getRequestMethod()) {
                case "GET" -> handleGet(exchange);
                case "PUT" -> handlePut(exchange);
                default -> JsonHttp.sendError(exchange, 405, "許可されていないメソッドです");
            }
        } finally {
            exchange.close();
        }
    }

    private void handleGet(HttpExchange exchange) throws IOException {
        JsonHttp.writeJson(exchange, 200, currentConfig.get());
    }

    private void handlePut(HttpExchange exchange) throws IOException {
        if (!JsonHttp.isOriginAllowed(exchange, port)) {
            JsonHttp.sendError(exchange, 403, "許可されていないOriginからのリクエストです");
            return;
        }
        AppConfig parsed;
        try {
            parsed = JsonHttp.readJson(exchange, AppConfig.class);
        } catch (JsonProcessingException e) {
            JsonHttp.sendError(exchange, 400, friendlyMessage(e));
            return;
        }
        try {
            AppConfigWriter.write(configPath, parsed);
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "config.yml の書き込みに失敗しました", e);
            JsonHttp.sendError(exchange, 500, "config.yml への書き込みに失敗しました: " + e.getMessage());
            return;
        }
        currentConfig.set(parsed);
        JsonHttp.writeJson(exchange, 200, parsed);
    }

    /** Jacksonがrecordコンパクトコンストラクタの例外をラップするため、根本原因のメッセージまで辿る。 */
    private static String friendlyMessage(JsonProcessingException e) {
        Throwable cause = e;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message != null ? message : "設定内容の検証に失敗しました";
    }
}
