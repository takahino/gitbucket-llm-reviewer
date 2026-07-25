package io.github.takahino.llmreviewer.review;

import com.sun.net.httpserver.HttpServer;
import io.github.takahino.llmreviewer.config.AppConfig;
import io.github.takahino.llmreviewer.gitbucket.GitBucketClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommentPublisherTest {

    private static final String BOT_COMMENT_BODY = CommentFormatter.marker("old-sha") + "\n## 変更サマリ\n過去のレビュー内容";
    private static final String HUMAN_COMMENT_BODY = "人間が書いた普通のコメント";

    private HttpServer server;
    private final List<String[]> requests = new CopyOnWriteArrayList<>(); // [method, path]
    private final Map<Long, String> patchedBodies = new ConcurrentHashMap<>();

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void startServer(int listCommentsStatus) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v3/repos/owner/repo/issues/5/comments", exchange -> {
            requests.add(new String[]{exchange.getRequestMethod(), exchange.getRequestURI().getPath()});
            exchange.getRequestBody().readAllBytes();
            if ("GET".equals(exchange.getRequestMethod())) {
                if (listCommentsStatus != 200) {
                    respond(exchange, listCommentsStatus, "error");
                    return;
                }
                String body = "[{\"id\":10,\"body\":%s},{\"id\":11,\"body\":%s}]"
                        .formatted(jsonString(BOT_COMMENT_BODY), jsonString(HUMAN_COMMENT_BODY));
                respond(exchange, 200, body);
            } else {
                respond(exchange, 201, "{\"id\":99,\"body\":\"new\"}");
            }
        });
        server.createContext("/api/v3/repos/owner/repo/issues/comments/10", exchange -> {
            requests.add(new String[]{exchange.getRequestMethod(), exchange.getRequestURI().getPath()});
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            patchedBodies.put(10L, requestBody);
            respond(exchange, 200, "{\"id\":10,\"body\":\"folded\"}");
        });
        server.start();
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String jsonString(String raw) {
        return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    private GitBucketClient clientFor() {
        return new GitBucketClient(new AppConfig.GitBucketConfig("http://127.0.0.1:" + server.getAddress().getPort(), "token", null, null));
    }

    @Test
    void foldsOnlyBotCommentsBeforePostingNew() throws IOException {
        startServer(200);
        CommentPublisher publisher = new CommentPublisher(clientFor(), true, false);

        publisher.publish("owner", "repo", 5, "新しいレビュー結果");

        assertTrue(patchedBodies.get(10L).contains("過去のレビュー内容"), "bot自身のコメントは折りたたみ対象になる");
        assertTrue(patchedBodies.get(10L).contains("<details>"));
        assertFalse(requests.stream().anyMatch(r -> r[0].equals("PATCH") && r[1].endsWith("/11")),
                "人間のコメントは編集対象にならない");
        assertTrue(requests.stream().anyMatch(r -> r[0].equals("POST")), "新規コメントが投稿されること");
    }

    @Test
    void dryRunSkipsAllHttpCalls() throws IOException {
        startServer(200);
        CommentPublisher publisher = new CommentPublisher(clientFor(), true, true);

        publisher.publish("owner", "repo", 5, "新しいレビュー結果");

        assertTrue(requests.isEmpty(), "dry-run時はAPIを一切呼ばない");
    }

    @Test
    void foldPreviousCommentsDisabledSkipsListingAndPatch() throws IOException {
        startServer(200);
        CommentPublisher publisher = new CommentPublisher(clientFor(), false, false);

        publisher.publish("owner", "repo", 5, "新しいレビュー結果");

        assertFalse(requests.stream().anyMatch(r -> r[0].equals("GET")), "折りたたみ無効時は一覧取得しない");
        assertTrue(requests.stream().anyMatch(r -> r[0].equals("POST")));
    }

    @Test
    void listCommentsFailureDoesNotBlockNewPost() throws IOException {
        startServer(500);
        CommentPublisher publisher = new CommentPublisher(clientFor(), true, false);

        publisher.publish("owner", "repo", 5, "新しいレビュー結果");

        assertTrue(requests.stream().anyMatch(r -> r[0].equals("POST")), "一覧取得に失敗しても新規投稿は継続する");
    }

    @Test
    void alreadyFoldedCommentIsNotFoldedAgain() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String foldedBody = "<details>\n<summary>過去のレビュー結果</summary>\n\n" + BOT_COMMENT_BODY + "\n\n</details>\n";
        server.createContext("/api/v3/repos/owner/repo/issues/5/comments", exchange -> {
            requests.add(new String[]{exchange.getRequestMethod(), exchange.getRequestURI().getPath()});
            exchange.getRequestBody().readAllBytes();
            if ("GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 200, "[{\"id\":10,\"body\":%s}]".formatted(jsonString(foldedBody)));
            } else {
                respond(exchange, 201, "{\"id\":99,\"body\":\"new\"}");
            }
        });
        server.createContext("/api/v3/repos/owner/repo/issues/comments/10", exchange -> {
            requests.add(new String[]{exchange.getRequestMethod(), exchange.getRequestURI().getPath()});
            exchange.getRequestBody().readAllBytes();
            respond(exchange, 200, "{}");
        });
        server.start();

        CommentPublisher publisher = new CommentPublisher(clientFor(), true, false);
        publisher.publish("owner", "repo", 5, "新しいレビュー結果");

        assertFalse(requests.stream().anyMatch(r -> r[0].equals("PATCH")), "既に折りたたみ済みのコメントは再度折りたたまない");
    }
}
