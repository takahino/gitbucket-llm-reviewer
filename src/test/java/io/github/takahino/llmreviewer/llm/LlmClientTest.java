package io.github.takahino.llmreviewer.llm;

import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.exception.LangChain4jException;
import io.github.takahino.llmreviewer.config.AppConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LlmClientTest {

    private static final String SUCCESS_BODY =
            "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}]}";

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private AppConfig.LlmConfig configFor(String baseUrl, int retryMaxAttempts, int retryBackoffMs) {
        return new AppConfig.LlmConfig(baseUrl, "test-model", null, null, null, 5, retryMaxAttempts, retryBackoffMs);
    }

    @Test
    void retriesOn5xxThenSucceeds() throws IOException {
        AtomicInteger requestCount = new AtomicInteger(0);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            int count = requestCount.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            byte[] body = count < 3
                    ? "server error".getBytes(StandardCharsets.UTF_8)
                    : SUCCESS_BODY.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(count < 3 ? 503 : 200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();

        LlmClient client = new LlmClient(configFor("http://127.0.0.1:" + server.getAddress().getPort(), 3, 10));
        String result = client.chat(List.of(new UserMessage("hi")));

        assertEquals("ok", result);
        assertEquals(3, requestCount.get());
    }

    @Test
    void doesNotRetryOn4xx() throws IOException {
        AtomicInteger requestCount = new AtomicInteger(0);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            requestCount.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            byte[] body = "bad request".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(400, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();

        LlmClient client = new LlmClient(configFor("http://127.0.0.1:" + server.getAddress().getPort(), 3, 10));

        assertThrows(LangChain4jException.class, () -> client.chat(List.of(new UserMessage("hi"))));
        assertEquals(1, requestCount.get());
    }

    @Test
    void exhaustsRetriesOnPersistent5xx() throws IOException {
        AtomicInteger requestCount = new AtomicInteger(0);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            requestCount.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            byte[] body = "server error".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(503, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();

        LlmClient client = new LlmClient(configFor("http://127.0.0.1:" + server.getAddress().getPort(), 3, 10));

        assertThrows(LangChain4jException.class, () -> client.chat(List.of(new UserMessage("hi"))));
        assertEquals(3, requestCount.get());
    }
}
