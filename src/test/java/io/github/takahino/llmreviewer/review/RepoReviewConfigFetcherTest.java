package io.github.takahino.llmreviewer.review;

import com.sun.net.httpserver.HttpServer;
import io.github.takahino.llmreviewer.config.AppConfig;
import io.github.takahino.llmreviewer.config.RepoReviewConfig;
import io.github.takahino.llmreviewer.git.GitMirrorException;
import io.github.takahino.llmreviewer.git.RepositoryReader;
import io.github.takahino.llmreviewer.gitbucket.GitBucketClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepoReviewConfigFetcherTest {

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
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    /** 呼び出し有無を記録できるテスト用RepositoryReader。 */
    private static final class FakeRepositoryReader implements RepositoryReader {
        private final AtomicBoolean called = new AtomicBoolean(false);
        private Optional<String> result = Optional.empty();
        private RuntimeException toThrow;

        static FakeRepositoryReader returning(String content) {
            FakeRepositoryReader reader = new FakeRepositoryReader();
            reader.result = Optional.ofNullable(content);
            return reader;
        }

        static FakeRepositoryReader throwing(RuntimeException e) {
            FakeRepositoryReader reader = new FakeRepositoryReader();
            reader.toThrow = e;
            return reader;
        }

        boolean wasCalled() {
            return called.get();
        }

        @Override
        public List<String> listFiles(String owner, String repo, String ref, int maxFiles) {
            throw new UnsupportedOperationException("not used in this test");
        }

        @Override
        public Optional<String> readFile(String owner, String repo, String ref, String path) {
            called.set(true);
            if (toThrow != null) {
                throw toThrow;
            }
            return result;
        }
    }

    @Test
    void fetchRawUsesRestContentWithoutFallingBackToJGit() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v3/repos/owner/repo/contents/.review.yml", exchange -> {
            exchange.getRequestBody().readAllBytes();
            respond(exchange, 200, "perspectives:\n  - security");
        });
        server.start();
        FakeRepositoryReader jGitReader = FakeRepositoryReader.returning("should not be used");

        RepoReviewConfigFetcher fetcher =
                new RepoReviewConfigFetcher(clientFor(server.getAddress().getPort()), jGitReader);
        Optional<String> raw = fetcher.fetchRaw("owner", "repo", "master");

        assertTrue(raw.isPresent());
        assertTrue(raw.get().contains("security"));
        assertFalse(jGitReader.wasCalled());
    }

    @Test
    void fetchRawFallsBackToJGitOnApiException() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v3/repos/owner/repo/contents/.review.yml", exchange -> {
            exchange.getRequestBody().readAllBytes();
            respond(exchange, 500, "boom");
        });
        server.start();
        FakeRepositoryReader jGitReader = FakeRepositoryReader.returning("perspectives:\n  - from-jgit");

        RepoReviewConfigFetcher fetcher =
                new RepoReviewConfigFetcher(clientFor(server.getAddress().getPort()), jGitReader);
        Optional<String> raw = fetcher.fetchRaw("owner", "repo", "master");

        assertTrue(raw.isPresent());
        assertTrue(raw.get().contains("from-jgit"));
        assertTrue(jGitReader.wasCalled());
    }

    @Test
    void fetchRawReturns404WithoutFallingBackToJGit() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v3/repos/owner/repo/contents/.review.yml", exchange -> {
            exchange.getRequestBody().readAllBytes();
            respond(exchange, 404, "not found");
        });
        server.start();
        FakeRepositoryReader jGitReader = FakeRepositoryReader.returning("should not be used");

        RepoReviewConfigFetcher fetcher =
                new RepoReviewConfigFetcher(clientFor(server.getAddress().getPort()), jGitReader);
        Optional<String> raw = fetcher.fetchRaw("owner", "repo", "master");

        assertTrue(raw.isEmpty());
        assertFalse(jGitReader.wasCalled());
    }

    @Test
    void fetchParsedReturnsDefaultConfigWhenNotFound() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v3/repos/owner/repo/contents/.review.yml", exchange -> {
            exchange.getRequestBody().readAllBytes();
            respond(exchange, 404, "not found");
        });
        server.start();

        RepoReviewConfigFetcher fetcher = new RepoReviewConfigFetcher(
                clientFor(server.getAddress().getPort()), FakeRepositoryReader.returning(null));
        RepoReviewConfig config = fetcher.fetchParsed("owner", "repo", "master");

        assertEquals(RepoReviewConfig.defaultConfig(), config);
    }

    @Test
    void fetchFileUsesJGitContentWithoutCallingRest() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v3/repos/owner/repo/contents/.review/naming.md", exchange -> {
            exchange.getRequestBody().readAllBytes();
            respond(exchange, 200, "should not be used");
        });
        server.start();
        FakeRepositoryReader jGitReader = FakeRepositoryReader.returning("# naming rules");

        RepoReviewConfigFetcher fetcher =
                new RepoReviewConfigFetcher(clientFor(server.getAddress().getPort()), jGitReader);
        Optional<String> content = fetcher.fetchFile("owner", "repo", "abc123", ".review/naming.md");

        assertEquals(Optional.of("# naming rules"), content);
    }

    @Test
    void fetchFileFallsBackToRestWhenJGitEmpty() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v3/repos/owner/repo/contents/.review/naming.md", exchange -> {
            exchange.getRequestBody().readAllBytes();
            respond(exchange, 200, "# from rest");
        });
        server.start();
        FakeRepositoryReader jGitReader = FakeRepositoryReader.returning(null);

        RepoReviewConfigFetcher fetcher =
                new RepoReviewConfigFetcher(clientFor(server.getAddress().getPort()), jGitReader);
        Optional<String> content = fetcher.fetchFile("owner", "repo", "abc123", ".review/naming.md");

        assertEquals(Optional.of("# from rest"), content);
    }

    @Test
    void fetchFileFallsBackToRestWhenJGitThrows() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v3/repos/owner/repo/contents/.review/naming.md", exchange -> {
            exchange.getRequestBody().readAllBytes();
            respond(exchange, 200, "# from rest after jgit failure");
        });
        server.start();
        FakeRepositoryReader jGitReader = FakeRepositoryReader.throwing(new GitMirrorException("mirror missing"));

        RepoReviewConfigFetcher fetcher =
                new RepoReviewConfigFetcher(clientFor(server.getAddress().getPort()), jGitReader);
        Optional<String> content = fetcher.fetchFile("owner", "repo", "abc123", ".review/naming.md");

        assertEquals(Optional.of("# from rest after jgit failure"), content);
    }

    @Test
    void fetchFileReturnsEmptyWhenBothFail() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v3/repos/owner/repo/contents/.review/naming.md", exchange -> {
            exchange.getRequestBody().readAllBytes();
            respond(exchange, 404, "not found");
        });
        server.start();
        FakeRepositoryReader jGitReader = FakeRepositoryReader.returning(null);

        RepoReviewConfigFetcher fetcher =
                new RepoReviewConfigFetcher(clientFor(server.getAddress().getPort()), jGitReader);
        Optional<String> content = fetcher.fetchFile("owner", "repo", "abc123", ".review/naming.md");

        assertTrue(content.isEmpty());
    }
}
