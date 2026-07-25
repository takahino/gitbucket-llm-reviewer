package io.github.takahino.llmreviewer.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 管理UIの単一HTML(classpathリソース1点)のみを返す。ファイルシステムを読まないため
 * ディレクトリトラバーサルの懸念が構造的に発生しない。
 */
final class StaticAssetHandler implements HttpHandler {

    private static final String RESOURCE_PATH = "/webui/admin.html";

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            byte[] content = readResource();
            if (content == null) {
                exchange.sendResponseHeaders(500, -1);
                return;
            }
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, content.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(content);
            }
        } finally {
            exchange.close();
        }
    }

    private byte[] readResource() throws IOException {
        try (InputStream in = getClass().getResourceAsStream(RESOURCE_PATH)) {
            return in == null ? null : in.readAllBytes();
        }
    }
}
