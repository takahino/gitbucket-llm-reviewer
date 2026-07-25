package io.github.takahino.llmreviewer;

import dev.langchain4j.model.embedding.EmbeddingModel;
import io.github.takahino.llmreviewer.config.AppConfig;
import io.github.takahino.llmreviewer.config.AppConfigLoader;
import io.github.takahino.llmreviewer.git.ApiDiffProvider;
import io.github.takahino.llmreviewer.git.JGitDiffProvider;
import io.github.takahino.llmreviewer.gitbucket.GitBucketClient;
import io.github.takahino.llmreviewer.llm.LlmClient;
import io.github.takahino.llmreviewer.rag.EmbeddingModelFactory;
import io.github.takahino.llmreviewer.rag.EmbeddingRagContextResolver;
import io.github.takahino.llmreviewer.rag.KnowledgeBaseIndexService;
import io.github.takahino.llmreviewer.rag.NoOpRagContextResolver;
import io.github.takahino.llmreviewer.rag.RagContextResolver;
import io.github.takahino.llmreviewer.rag.RagIndexStateStore;
import io.github.takahino.llmreviewer.rag.RepoCodeIndexService;
import io.github.takahino.llmreviewer.review.PollingService;
import io.github.takahino.llmreviewer.review.ReviewOrchestrator;
import io.github.takahino.llmreviewer.review.ReviewStateStore;
import io.github.takahino.llmreviewer.util.LogSetup;

import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class Main {

    private static final Logger LOGGER = Logger.getLogger(Main.class.getName());

    public static void main(String[] args) {
        LogSetup.init(Level.INFO);
        try {
            run(args);
        } catch (RuntimeException e) {
            // 未捕捉例外がデフォルトのスタックトレース出力(プラットフォーム既定エンコーディング)で
            // 文字化けするのを避けるため、必ずロガー経由でUTF-8出力する
            LOGGER.log(Level.SEVERE, "起動に失敗しました", e);
            System.exit(1);
        }
    }

    private static void run(String[] args) {
        Arguments arguments = Arguments.parse(args);
        AppConfig config = AppConfigLoader.load(Path.of(arguments.configPath()));
        logStartupSummary(config, arguments);

        GitBucketClient gitBucketClient = new GitBucketClient(config.gitbucket());
        JGitDiffProvider jGitProvider = new JGitDiffProvider(Path.of(config.workDir()), config.gitbucket());
        ApiDiffProvider apiFallbackProvider = new ApiDiffProvider(gitBucketClient);
        LlmClient llmClient = new LlmClient(config.llm());
        ReviewStateStore stateStore = new ReviewStateStore(Path.of(config.state().filePath()));
        RagContextResolver ragContextResolver = createRagContextResolver(config.rag(), jGitProvider);

        ReviewOrchestrator orchestrator = new ReviewOrchestrator(
                gitBucketClient, jGitProvider, apiFallbackProvider, llmClient, stateStore,
                config.review(), config.llm().model(), arguments.dryRun(), ragContextResolver);

        PollingService pollingService = new PollingService(
                gitBucketClient, orchestrator, config.repositories(), config.polling().intervalSeconds());

        if (arguments.once()) {
            pollingService.runOnce();
            orchestrator.close();
            LOGGER.info("--once 指定のため、1回の走査で終了します");
        } else {
            LOGGER.info("ポーリングを開始します(間隔: %d秒)".formatted(config.polling().intervalSeconds()));
            pollingService.startPolling();
        }
    }

    private static RagContextResolver createRagContextResolver(AppConfig.RagConfig ragConfig, JGitDiffProvider jGitProvider) {
        if (!ragConfig.enabled()) {
            return new NoOpRagContextResolver();
        }
        EmbeddingModel embeddingModel = EmbeddingModelFactory.create(ragConfig);
        RagIndexStateStore ragIndexStateStore = new RagIndexStateStore(Path.of(ragConfig.indexDir()).resolve("index-state.json"));
        RepoCodeIndexService codeIndexService =
                new RepoCodeIndexService(jGitProvider, embeddingModel, ragIndexStateStore, ragConfig);
        KnowledgeBaseIndexService knowledgeBaseIndexService =
                new KnowledgeBaseIndexService(jGitProvider, embeddingModel, ragIndexStateStore, ragConfig);
        return new EmbeddingRagContextResolver(codeIndexService, knowledgeBaseIndexService, embeddingModel, ragConfig);
    }

    private static void logStartupSummary(AppConfig config, Arguments arguments) {
        LOGGER.info("gitbucket-llm-reviewer を起動します");
        LOGGER.info("GitBucket: %s (token=%s)".formatted(config.gitbucket().baseUrl(), mask(config.gitbucket().token())));
        LOGGER.info("監視対象リポジトリ: %s".formatted(
                config.repositories().stream().map(AppConfig.RepositoryRef::fullName).toList()));
        LOGGER.info("LLM: %s (model=%s)".formatted(config.llm().baseUrl(), config.llm().model()));
        LOGGER.info("dry-run=%s, once=%s".formatted(arguments.dryRun(), arguments.once()));
    }

    private static String mask(String token) {
        if (token.length() <= 4) {
            return "****";
        }
        return token.substring(0, 4) + "****";
    }

    private record Arguments(String configPath, boolean once, boolean dryRun) {
        static Arguments parse(String[] args) {
            String configPath = "./config.yml";
            boolean once = false;
            boolean dryRun = false;
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--config" -> configPath = args[++i];
                    case "--once" -> once = true;
                    case "--dry-run" -> dryRun = true;
                    default -> throw new IllegalArgumentException("不明な引数です: " + args[i]);
                }
            }
            return new Arguments(configPath, once, dryRun);
        }
    }
}
