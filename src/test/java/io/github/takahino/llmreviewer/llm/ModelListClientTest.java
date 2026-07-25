package io.github.takahino.llmreviewer.llm;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelListClientTest {

    private HttpServer server;
    private final ModelListClient client = new ModelListClient();

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
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
    void listOpenAiCompatibleParsesModelIds() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> authHeader = new AtomicReference<>();
        server.createContext("/v1/models", exchange -> {
            authHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, "{\"data\":[{\"id\":\"model-a\"},{\"id\":\"model-b\"}]}");
        });
        server.start();

        ModelListClient.Result result =
                client.listOpenAiCompatible("http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "secret-key");

        assertTrue(result.ok());
        assertEquals(java.util.List.of("model-a", "model-b"), result.models());
        assertNull(result.error());
        assertEquals("Bearer secret-key", authHeader.get());
    }

    @Test
    void listOpenAiCompatibleOmitsAuthHeaderWhenApiKeyBlank() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> authHeader = new AtomicReference<>();
        server.createContext("/v1/models", exchange -> {
            authHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, "{\"data\":[]}");
        });
        server.start();

        client.listOpenAiCompatible("http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "");

        assertNull(authHeader.get());
    }

    @Test
    void listOpenAiCompatibleReturnsFailureOnNon200() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> respond(exchange, 404, "not found"));
        server.start();

        ModelListClient.Result result =
                client.listOpenAiCompatible("http://127.0.0.1:" + server.getAddress().getPort() + "/v1", null);

        assertFalse(result.ok());
        assertTrue(result.models().isEmpty());
        assertTrue(result.error().contains("404"));
    }

    @Test
    void listOpenAiCompatibleReturnsFailureWhenUnreachable() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }

        ModelListClient.Result result =
                client.listOpenAiCompatible("http://127.0.0.1:" + closedPort + "/v1", null);

        assertFalse(result.ok());
        assertTrue(result.models().isEmpty());
    }

    @Test
    void listOllamaParsesModelNames() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/tags", exchange ->
                respond(exchange, 200, "{\"models\":[{\"name\":\"llama3\"},{\"name\":\"nomic-embed-text\"}]}"));
        server.start();

        ModelListClient.Result result = client.listOllama("http://127.0.0.1:" + server.getAddress().getPort());

        assertTrue(result.ok());
        assertEquals(java.util.List.of("llama3", "nomic-embed-text"), result.models());
    }

    @Test
    void listOllamaReturnsFailureOnNon200() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/tags", exchange -> respond(exchange, 500, "boom"));
        server.start();

        ModelListClient.Result result = client.listOllama("http://127.0.0.1:" + server.getAddress().getPort());

        assertFalse(result.ok());
        assertTrue(result.error().contains("500"));
    }
}
