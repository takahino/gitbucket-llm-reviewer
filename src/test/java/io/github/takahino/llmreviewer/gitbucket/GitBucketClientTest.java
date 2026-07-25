package io.github.takahino.llmreviewer.gitbucket;

import com.sun.net.httpserver.HttpServer;
import io.github.takahino.llmreviewer.config.AppConfig;
import io.github.takahino.llmreviewer.gitbucket.model.IssueComment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitBucketClientTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private GitBucketClient clientFor(int port) {
        AppConfig.GitBucketConfig config =
                new AppConfig.GitBucketConfig("http://127.0.0.1:" + port, "test-token", null, null);
        return new GitBucketClient(config);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    @Test
    void listIssueCommentsParsesResponse() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v3/repos/owner/repo/issues/5/comments", exchange -> {
            exchange.getRequestBody().readAllBytes();
            respond(exchange, 200, "[{\"id\":1,\"body\":\"hello\"},{\"id\":2,\"body\":\"world\"}]");
        });
        server.start();

        List<IssueComment> comments = clientFor(server.getAddress().getPort())
                .listIssueComments("owner", "repo", 5);

        assertEquals(2, comments.size());
        assertEquals(1, comments.get(0).id());
        assertEquals("hello", comments.get(0).body());
        assertEquals("world", comments.get(1).body());
    }

    @Test
    void updateIssueCommentSendsPatchWithNewBody() throws IOException {
        AtomicReference<String> receivedMethod = new AtomicReference<>();
        AtomicReference<String> receivedBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v3/repos/owner/repo/issues/comments/42", exchange -> {
            receivedMethod.set(exchange.getRequestMethod());
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, "{\"id\":42,\"body\":\"folded\"}");
        });
        server.start();

        clientFor(server.getAddress().getPort()).updateIssueComment("owner", "repo", 42, "folded body");

        assertEquals("PATCH", receivedMethod.get());
        assertTrue(receivedBody.get().contains("folded body"));
    }

    @Test
    void postIssueCommentSendsPost() throws IOException {
        AtomicReference<String> receivedMethod = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v3/repos/owner/repo/issues/5/comments", exchange -> {
            receivedMethod.set(exchange.getRequestMethod());
            exchange.getRequestBody().readAllBytes();
            respond(exchange, 201, "{\"id\":99,\"body\":\"new\"}");
        });
        server.start();

        clientFor(server.getAddress().getPort()).postIssueComment("owner", "repo", 5, "new comment");

        assertEquals("POST", receivedMethod.get());
    }

    @Test
    void nonSuccessStatusThrowsWithStatusCode() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v3/repos/owner/repo/issues/comments/1", exchange -> {
            exchange.getRequestBody().readAllBytes();
            respond(exchange, 403, "forbidden");
        });
        server.start();

        GitBucketClient client = clientFor(server.getAddress().getPort());
        GitBucketApiException ex = assertThrows(GitBucketApiException.class,
                () -> client.updateIssueComment("owner", "repo", 1, "x"));
        assertEquals(403, ex.statusCode());
    }
}
