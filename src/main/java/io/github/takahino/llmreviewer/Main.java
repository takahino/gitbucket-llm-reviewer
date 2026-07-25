package io.github.takahino.llmreviewer;

import dev.langchain4j.model.embedding.EmbeddingModel;
import io.github.takahino.llmreviewer.config.AppConfig;
import io.github.takahino.llmreviewer.config.AppConfigLoader;
import io.github.takahino.llmreviewer.git.ApiDiffProvider;
import io.github.takahino.llmreviewer.git.JGitDiffProvider;
import io.github.takahino.llmreviewer.llm.LlmClient;
import io.github.takahino.llmreviewer.rag.EmbeddingModelFactory;
import io.github.takahino.llmreviewer.rag.EmbeddingRagContextResolver;
import io.github.takahino.llmreviewer.rag.KnowledgeBaseIndexService;
import io.github.takahino.llmreviewer.rag.NoOpRagContextResolver;
import io.github.takahino.llmreviewer.rag.RagContextResolver;
import io.github.takahino.llmreviewer.rag.RagIndexStateStore;
import io.github.takahino.llmreviewer.rag.RepoCodeIndexService;
import io.github.takahino.llmreviewer.review.MentionReplyOrchestrator;
import io.github.takahino.llmreviewer.review.MentionStateStore;
import io.github.takahino.llmreviewer.review.PollingService;
import io.github.takahino.llmreviewer.review.RepoReviewConfigFetcher;
import io.github.takahino.llmreviewer.review.ReviewOrchestrator;
import io.github.takahino.llmreviewer.review.ReviewStateStore;
import io.github.takahino.llmreviewer.scm.BotIdentityResolver;
import io.github.takahino.llmreviewer.scm.ScmClient;
import io.github.takahino.llmreviewer.scm.ScmClientFactory;
import io.github.takahino.llmreviewer.util.LogSetup;
import io.github.takahino.llmreviewer.web.WebUiServer;

import java.io.IOException;
import java.io.UncheckedIOException;
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

        if (arguments.ui()) {
            runUiMode(config, Path.of(arguments.configPath()), arguments.uiPort());
            return;
        }

        logStartupSummary(config, arguments);

        ScmClientFactory.ScmBundle scm = ScmClientFactory.create(config);
        ScmClient scmClient = scm.client();
        JGitDiffProvider jGitProvider = new JGitDiffProvider(Path.of(config.workDir()), scm.remoteLocator());
        ApiDiffProvider apiFallbackProvider = new ApiDiffProvider(scmClient);
        LlmClient llmClient = new LlmClient(config.llm());
        ReviewStateStore stateStore = new ReviewStateStore(Path.of(config.state().filePath()), arguments.dryRun());
        RagContextResolver ragContextResolver = createRagContextResolver(config.rag(), jGitProvider);
        RepoReviewConfigFetcher repoReviewConfigFetcher = new RepoReviewConfigFetcher(scmClient, jGitProvider);

        ReviewOrchestrator orchestrator = new ReviewOrchestrator(
                scmClient, jGitProvider, apiFallbackProvider, llmClient, stateStore,
                config.review(), config.llm().model(), arguments.dryRun(), ragContextResolver,
                repoReviewConfigFetcher);

        String botUsername = BotIdentityResolver.resolve(scmClient, config.gitbucket().botUsername())
                .orElse(null);
        if (botUsername == null) {
            LOGGER.warning("Botユーザー名を解決できなかったため、メンション応答機能を無効化します"
                    + "(gitbucket.botUsername を明示設定すると回避できます)");
        } else {
            LOGGER.info("メンション応答機能を有効化します(botUsername=%s)".formatted(botUsername));
        }
        MentionStateStore mentionStateStore =
                new MentionStateStore(Path.of(config.state().mentionStateFilePath()), arguments.dryRun());
        MentionReplyOrchestrator mentionReplyOrchestrator = new MentionReplyOrchestrator(
                scmClient, jGitProvider, apiFallbackProvider, llmClient, mentionStateStore,
                config.review(), config.llm().model(), arguments.dryRun(), ragContextResolver,
                repoReviewConfigFetcher, botUsername);

        PollingService pollingService = new PollingService(
                scmClient, orchestrator, mentionReplyOrchestrator,
                config.repositories(), config.polling().intervalSeconds());

        if (arguments.once()) {
            pollingService.runOnce();
            orchestrator.close();
            LOGGER.info("--once 指定のため、1回の走査で終了します");
        } else {
            LOGGER.info("ポーリングを開始します(間隔: %d秒)".formatted(config.polling().intervalSeconds()));
            pollingService.startPolling();
        }
    }

    /**
     * config.yml編集・review.yml表示専用の管理UIサーバーを起動する(ポーリングは行わない)。
     * AppConfigは不変recordで起動時に一度だけ読み込まれる設計のため、実行中のポーリングプロセスに
     * config.yml編集を反映するホットリロードの仕組みは無い。UIでの編集後は通常起動で再起動して反映する。
     */
    private static void runUiMode(AppConfig config, Path configPath, int port) {
        ScmClientFactory.ScmBundle scm = ScmClientFactory.create(config);
        JGitDiffProvider jGitProvider = new JGitDiffProvider(Path.of(config.workDir()), scm.remoteLocator());
        RepoReviewConfigFetcher repoReviewConfigFetcher = new RepoReviewConfigFetcher(scm.client(), jGitProvider);

        WebUiServer server;
        try {
            server = new WebUiServer(port, configPath, config, scm.client(), repoReviewConfigFetcher);
        } catch (IOException e) {
            jGitProvider.close();
            throw new UncheckedIOException("管理UIサーバーの起動に失敗しました(port=%d)".formatted(port), e);
        }
        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOGGER.info("シャットダウン要求を受信、管理UIを停止します");
            server.stop();
            jGitProvider.close();
        }, "ui-shutdown-hook"));
        LOGGER.info("管理UIを起動しました: http://127.0.0.1:%d/ (Ctrl+Cで終了、config.yml=%s)".formatted(port, configPath));
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
        LOGGER.info("接続先(%s): %s (token=%s)"
                .formatted(config.provider(), config.gitbucket().baseUrl(), mask(config.gitbucket().token())));
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

    private record Arguments(String configPath, boolean once, boolean dryRun, boolean ui, int uiPort) {
        private static final int DEFAULT_UI_PORT = 8765;

        static Arguments parse(String[] args) {
            String configPath = "./config.yml";
            boolean once = false;
            boolean dryRun = false;
            boolean ui = false;
            int uiPort = DEFAULT_UI_PORT;
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--config" -> configPath = args[++i];
                    case "--once" -> once = true;
                    case "--dry-run" -> dryRun = true;
                    case "--ui" -> ui = true;
                    case "--ui-port" -> uiPort = Integer.parseInt(args[++i]);
                    default -> throw new IllegalArgumentException("不明な引数です: " + args[i]);
                }
            }
            if (ui && (once || dryRun)) {
                throw new IllegalArgumentException("--ui は --once/--dry-run と併用できません");
            }
            return new Arguments(configPath, once, dryRun, ui, uiPort);
        }
    }
}
