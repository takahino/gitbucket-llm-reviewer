package io.github.takahino.llmreviewer.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;

/**
 * 管理UIの単一HTML(classpathリソース1点)のみを返す。ファイルシステムを読まないため
 * ディレクトリトラバーサルの懸念が構造的に発生しない。リソースはプロセス生存期間中不変なので
 * クラス初期化時に一度だけ読み込み、リクエスト毎の再読み込みを避ける。
 */
final class StaticAssetHandler implements HttpHandler {

    private static final String RESOURCE_PATH = "/webui/admin.html";
    private static final byte[] CONTENT = loadResource();

    private static byte[] loadResource() {
        try (InputStream in = StaticAssetHandler.class.getResourceAsStream(RESOURCE_PATH)) {
            if (in == null) {
                throw new IllegalStateException("classpathリソースが見つかりません: " + RESOURCE_PATH);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, CONTENT.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(CONTENT);
            }
        } finally {
            exchange.close();
        }
    }
}
