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
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommentPublisherTest {

    private HttpServer server;
    private final List<String[]> requests = new CopyOnWriteArrayList<>(); // [method, path]
    private final List<String> postedBodies = new CopyOnWriteArrayList<>();

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v3/repos/owner/repo/issues/5/comments", exchange -> {
            requests.add(new String[]{exchange.getRequestMethod(), exchange.getRequestURI().getPath()});
            byte[] requestBytes = exchange.getRequestBody().readAllBytes();
            postedBodies.add(new String(requestBytes, StandardCharsets.UTF_8));
            respond(exchange, 201, "{\"id\":99,\"body\":\"new\"}");
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

    private GitBucketClient clientFor() {
        return new GitBucketClient(new AppConfig.GitBucketConfig("http://127.0.0.1:" + server.getAddress().getPort(), "token", null, null));
    }

    @Test
    void dryRunSkipsAllHttpCalls() throws IOException {
        startServer();
        CommentPublisher publisher = new CommentPublisher(clientFor(), true);

        publisher.publish("owner", "repo", 5, List.of("新しいレビュー結果"));

        assertTrue(requests.isEmpty(), "dry-run時はAPIを一切呼ばない");
    }

    @Test
    void postsMultipleCommentsInOrder() throws IOException {
        startServer();
        CommentPublisher publisher = new CommentPublisher(clientFor(), false);

        publisher.publish("owner", "repo", 5, List.of("サマリコメント", "指摘事項コメント"));

        long postCount = requests.stream().filter(r -> r[0].equals("POST")).count();
        assertEquals(2, postCount, "渡したコメント本文の数だけPOSTされること");
        assertEquals(2, postedBodies.size());
        assertTrue(postedBodies.get(0).contains("サマリコメント"), "1件目はサマリコメントであること");
        assertTrue(postedBodies.get(1).contains("指摘事項コメント"), "2件目は指摘事項コメントであること");
    }
}
