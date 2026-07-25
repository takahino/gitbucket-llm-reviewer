package io.github.takahino.llmreviewer.web;

import com.sun.net.httpserver.HttpServer;
import io.github.takahino.llmreviewer.config.AppConfig;
import io.github.takahino.llmreviewer.git.RepositoryReader;
import io.github.takahino.llmreviewer.gitbucket.GitBucketClient;
import io.github.takahino.llmreviewer.review.RepoReviewConfigFetcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReviewYmlApiHandlerTest {

    private static final String REVIEW_YML = """
            language: ja
            perspectives:
              - perspective: security
                context:
                  - security-checklist.md
            paths:
              "src/payment/**":
                perspectives:
                  - perspective: payment domain
                    context:
                      - payment-flow.md
            maxComments: 5
            """;

    /** JGitミラー未fetch状態を模し、常にREST APIフォールバック側の経路だけを通す。 */
    private static final class AlwaysEmptyRepositoryReader implements RepositoryReader {
        @Override
        public List<String> listFiles(String owner, String repo, String ref, int maxFiles) {
            return List.of();
        }

        @Override
        public Optional<String> readFile(String owner, String repo, String ref, String path) {
            return Optional.empty();
        }
    }

    private HttpServer gitBucketMock;
    private HttpServer uiServer;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    @AfterEach
    void stopServers() {
        if (uiServer != null) {
            uiServer.stop(0);
        }
        if (gitBucketMock != null) {
            gitBucketMock.stop(0);
        }
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private int startUiServerWith(GitBucketClient client) throws IOException {
        RepoReviewConfigFetcher fetcher = new RepoReviewConfigFetcher(client, new AlwaysEmptyRepositoryReader());
        uiServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = uiServer.getAddress().getPort();
        uiServer.createContext("/api/review-yml", new ReviewYmlApiHandler(client, fetcher));
        uiServer.start();
        return port;
    }

    private GitBucketClient gitBucketClientFor(int mockPort) {
        return new GitBucketClient(new AppConfig.GitBucketConfig("http://127.0.0.1:" + mockPort, "test-token", null, null, null));
    }

    @Test
    void returnsParsedReviewYmlWithContextFileContents() throws Exception {
        gitBucketMock = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        gitBucketMock.createContext("/api/v3/repos/owner/repo", exchange -> {
            if (exchange.getRequestURI().getPath().equals("/api/v3/repos/owner/repo")) {
                respond(exchange, 200, "{\"name\":\"repo\",\"full_name\":\"owner/repo\",\"default_branch\":\"main\"}");
            } else {
                respond(exchange, 404, "not found");
            }
        });
        gitBucketMock.createContext("/api/v3/repos/owner/repo/contents/.review.yml", exchange ->
                respond(exchange, 200, REVIEW_YML));
        gitBucketMock.createContext("/api/v3/repos/owner/repo/contents/.review/security-checklist.md", exchange ->
                respond(exchange, 200, "# security checklist"));
        gitBucketMock.createContext("/api/v3/repos/owner/repo/contents/.review/payment-flow.md", exchange ->
                respond(exchange, 200, "# payment flow"));
        gitBucketMock.start();

        int uiPort = startUiServerWith(gitBucketClientFor(gitBucketMock.getAddress().getPort()));

        HttpResponse<String> response = httpClient.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + uiPort + "/api/review-yml?owner=owner&repo=repo")).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertEquals(200, response.statusCode());
        String body = response.body();
        assertTrue(body.contains("\"found\":true"));
        assertTrue(body.contains("security checklist"));
        assertTrue(body.contains("payment flow"));
        assertTrue(body.contains("src/payment/**"));
        assertTrue(body.contains("共通"));
    }

    @Test
    void returnsNotFoundWhenReviewYmlMissing() throws Exception {
        gitBucketMock = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        gitBucketMock.createContext("/api/v3/repos/owner/repo", exchange -> {
            if (exchange.getRequestURI().getPath().equals("/api/v3/repos/owner/repo")) {
                respond(exchange, 200, "{\"name\":\"repo\",\"full_name\":\"owner/repo\",\"default_branch\":\"main\"}");
            } else {
                respond(exchange, 404, "not found");
            }
        });
        gitBucketMock.start();

        int uiPort = startUiServerWith(gitBucketClientFor(gitBucketMock.getAddress().getPort()));

        HttpResponse<String> response = httpClient.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + uiPort + "/api/review-yml?owner=owner&repo=repo")).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"found\":false"));
        assertTrue(response.body().contains("\"reviewContextFiles\":[]"));
    }

    @Test
    void missingQueryParamsReturns400() throws Exception {
        gitBucketMock = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        gitBucketMock.start();
        int uiPort = startUiServerWith(gitBucketClientFor(gitBucketMock.getAddress().getPort()));

        HttpResponse<String> response = httpClient.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + uiPort + "/api/review-yml")).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertEquals(400, response.statusCode());
    }

    @Test
    void repositoryLookupFailureReturns502() throws Exception {
        gitBucketMock = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        gitBucketMock.createContext("/api/v3/repos/owner/repo", exchange -> respond(exchange, 500, "boom"));
        gitBucketMock.start();

        int uiPort = startUiServerWith(gitBucketClientFor(gitBucketMock.getAddress().getPort()));

        HttpResponse<String> response = httpClient.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + uiPort + "/api/review-yml?owner=owner&repo=repo")).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertEquals(502, response.statusCode());
        assertFalse(response.body().isBlank());
    }
}
