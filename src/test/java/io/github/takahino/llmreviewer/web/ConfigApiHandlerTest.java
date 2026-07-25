package io.github.takahino.llmreviewer.web;

import com.sun.net.httpserver.HttpServer;
import io.github.takahino.llmreviewer.config.AppConfig;
import io.github.takahino.llmreviewer.config.AppConfigLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigApiHandlerTest {

    private HttpServer server;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private AppConfig sampleConfig(String baseUrl) {
        return new AppConfig(
                new AppConfig.GitBucketConfig(baseUrl, "token-value", "", "", ""),
                List.of(new AppConfig.RepositoryRef("owner", "repo")),
                new AppConfig.PollingConfig(30),
                new AppConfig.LlmConfig("http://localhost:11434/v1", "qwen2.5-coder:14b", "", 0.2, 4096, 300, 3, 2000),
                new AppConfig.ReviewConfig(60000, 5, 50000, 3),
                new AppConfig.RagConfig(
                        false, "ollama", "http://localhost:11434", "nomic-embed-text", "", 5, 0.65, 500, 50, 3000,
                        List.of(".java", ".md"), "./data/rag-index"),
                new AppConfig.StateConfig("./data/review-state.json", "./data/mention-state.json"),
                "./data/repos"
        );
    }

    private int startServer(Path configPath, AppConfig initial) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        server.createContext("/api/config", new ConfigApiHandler(configPath, new AtomicReference<>(initial), port));
        server.start();
        return port;
    }

    @Test
    void getReturnsCurrentConfig(@TempDir Path tempDir) throws Exception {
        Path configPath = tempDir.resolve("config.yml");
        int port = startServer(configPath, sampleConfig("http://localhost:8080"));

        HttpResponse<String> response = httpClient.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/config")).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("http://localhost:8080"));
    }

    @Test
    void putValidConfigWritesFileAndReturns200(@TempDir Path tempDir) throws Exception {
        Path configPath = tempDir.resolve("config.yml");
        int port = startServer(configPath, sampleConfig("http://localhost:8080"));
        String body = JsonHttp.MAPPER.writeValueAsString(sampleConfig("http://localhost:9090"));

        HttpResponse<String> response = httpClient.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/config"))
                        .PUT(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertEquals(200, response.statusCode());
        assertTrue(Files.exists(configPath));
        AppConfig reloaded = AppConfigLoader.load(configPath);
        assertEquals("http://localhost:9090", reloaded.gitbucket().baseUrl());
    }

    @Test
    void putInvalidConfigReturns400AndDoesNotWriteFile(@TempDir Path tempDir) throws Exception {
        Path configPath = tempDir.resolve("config.yml");
        int port = startServer(configPath, sampleConfig("http://localhost:8080"));
        // gitbucket.baseUrl を空文字にした不正なJSON(AppConfig.GitBucketConfigのコンパクトコンストラクタで検証エラーになる)
        String invalidBody = JsonHttp.MAPPER.writeValueAsString(sampleConfig("http://localhost:8080"))
                .replace("http://localhost:8080", "");

        HttpResponse<String> response = httpClient.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/config"))
                        .PUT(HttpRequest.BodyPublishers.ofString(invalidBody, StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("baseUrl"));
        assertFalse(Files.exists(configPath));
    }

    @Test
    void putMalformedJsonReturns400(@TempDir Path tempDir) throws Exception {
        Path configPath = tempDir.resolve("config.yml");
        int port = startServer(configPath, sampleConfig("http://localhost:8080"));

        HttpResponse<String> response = httpClient.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/config"))
                        .PUT(HttpRequest.BodyPublishers.ofString("{not-json", StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertEquals(400, response.statusCode());
        assertFalse(Files.exists(configPath));
    }
}
