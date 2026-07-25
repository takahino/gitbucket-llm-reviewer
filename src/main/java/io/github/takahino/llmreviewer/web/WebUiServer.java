package io.github.takahino.llmreviewer.web;

import com.sun.net.httpserver.HttpServer;
import io.github.takahino.llmreviewer.config.AppConfig;
import io.github.takahino.llmreviewer.gitbucket.GitBucketClient;
import io.github.takahino.llmreviewer.llm.ModelListClient;
import io.github.takahino.llmreviewer.review.RepoReviewConfigFetcher;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * config.yml編集・review.yml表示のための管理UI HTTPサーバー。
 * config.ymlにはシークレットが含まれるため、127.0.0.1固定でのみ待ち受ける
 * (外部公開したい場合でもこのクラスではバインドアドレスを変更できないようにしている)。
 */
public final class WebUiServer {

    private final HttpServer server;
    private final ExecutorService executor;

    public WebUiServer(
            int port,
            Path configPath,
            AppConfig initialConfig,
            GitBucketClient gitBucketClient,
            RepoReviewConfigFetcher repoReviewConfigFetcher
    ) throws IOException {
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        this.executor = Executors.newFixedThreadPool(4);
        server.setExecutor(executor);

        AtomicReference<AppConfig> currentConfig = new AtomicReference<>(initialConfig);
        ModelListClient modelListClient = new ModelListClient();

        server.createContext("/", new StaticAssetHandler());
        server.createContext("/api/config", new ConfigApiHandler(configPath, currentConfig, port));
        server.createContext("/api/review-yml", new ReviewYmlApiHandler(gitBucketClient, repoReviewConfigFetcher));
        server.createContext("/api/llm/models", new LlmModelsApiHandler(modelListClient, port));
        server.createContext("/api/embedding/models", new EmbeddingModelsApiHandler(modelListClient, port));
    }

    public void start() {
        server.start();
    }

    public void stop() {
        server.stop(0);
        executor.shutdown();
    }

    public int port() {
        return server.getAddress().getPort();
    }
}
