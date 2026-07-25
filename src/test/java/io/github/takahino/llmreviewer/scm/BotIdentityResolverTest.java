package io.github.takahino.llmreviewer.scm;

import com.sun.net.httpserver.HttpServer;
import io.github.takahino.llmreviewer.config.AppConfig;
import io.github.takahino.llmreviewer.gitbucket.GitBucketClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BotIdentityResolverTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private GitBucketClient clientFor(int port) {
        AppConfig.GitBucketConfig config =
                new AppConfig.GitBucketConfig("http://127.0.0.1:" + port, "test-token", null, null, null);
        return new GitBucketClient(config);
    }

    @Test
    void configuredUsernameIsUsedWithoutCallingApi() {
        // 到達不可能なポートを指定し、API呼び出しが発生していれば例外になることで非呼び出しを検証する
        GitBucketClient client = clientFor(1);

        Optional<String> resolved = BotIdentityResolver.resolve(client, "configured-bot");

        assertEquals(Optional.of("configured-bot"), resolved);
    }

    @Test
    void resolvesFromApiWhenConfiguredUsernameIsBlank() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v3/user", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = "{\"login\":\"api-bot\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();

        Optional<String> resolved =
                BotIdentityResolver.resolve(clientFor(server.getAddress().getPort()), "");

        assertEquals(Optional.of("api-bot"), resolved);
    }

    @Test
    void returnsEmptyWhenApiCallFails() {
        GitBucketClient client = clientFor(1);

        Optional<String> resolved = BotIdentityResolver.resolve(client, null);

        assertTrue(resolved.isEmpty());
    }
}
