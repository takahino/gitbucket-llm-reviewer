package io.github.takahino.llmreviewer.config;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * ツール側設定(config.yml)。各ネストレコードのコンパクトコンストラクタで
 * 必須項目の検証とデフォルト値の適用を行う(設定ファイルはシステム境界のため)。
 */
public record AppConfig(
        String provider,
        GitBucketConfig gitbucket,
        List<RepositoryRef> repositories,
        PollingConfig polling,
        LlmConfig llm,
        ReviewConfig review,
        RagConfig rag,
        StateConfig state,
        String workDir
) {
    public AppConfig {
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("provider は必須です(現時点では gitbucket のみ指定可能です)");
        }
        provider = provider.trim().toLowerCase(Locale.ROOT);
        Objects.requireNonNull(gitbucket, "gitbucket 設定は必須です");
        if (repositories == null || repositories.isEmpty()) {
            throw new IllegalArgumentException("repositories には最低1件のリポジトリを指定してください");
        }
        repositories = List.copyOf(repositories);
        Objects.requireNonNull(llm, "llm 設定は必須です");
        polling = polling != null ? polling : new PollingConfig(null);
        review = review != null ? review : new ReviewConfig(null, null, null, null);
        rag = rag != null ? rag : new RagConfig(
                null, null, null, null, null, null, null, null, null, null, null, null);
        state = state != null ? state : new StateConfig(null, null);
        workDir = (workDir == null || workDir.isBlank()) ? "./data/repos" : workDir;
    }

    public record GitBucketConfig(String baseUrl, String token, String gitUsername, String gitPassword, String botUsername) {
        public GitBucketConfig {
            if (baseUrl == null || baseUrl.isBlank()) {
                throw new IllegalArgumentException("gitbucket.baseUrl は必須です");
            }
            if (token == null || token.isBlank()) {
                throw new IllegalArgumentException("gitbucket.token は必須です");
            }
            baseUrl = baseUrl.replaceAll("/+$", "");
            gitUsername = gitUsername == null ? "" : gitUsername;
            gitPassword = gitPassword == null ? "" : gitPassword;
            // 空ならBotIdentityResolverがGitBucket APIから自動解決する
            botUsername = botUsername == null ? "" : botUsername;
        }
    }

    public record RepositoryRef(String owner, String name) {
        public RepositoryRef {
            if (owner == null || owner.isBlank() || name == null || name.isBlank()) {
                throw new IllegalArgumentException("repositories[].owner と name は必須です");
            }
        }

        public String fullName() {
            return owner + "/" + name;
        }
    }

    public record PollingConfig(Integer intervalSeconds) {
        public PollingConfig {
            intervalSeconds = intervalSeconds == null ? 60 : intervalSeconds;
        }
    }

    public record LlmConfig(
            String baseUrl,
            String model,
            String apiKey,
            Double temperature,
            Integer maxTokens,
            Integer timeoutSeconds,
            Integer retryMaxAttempts,
            Integer retryBackoffMs
    ) {
        public LlmConfig {
            if (baseUrl == null || baseUrl.isBlank()) {
                throw new IllegalArgumentException("llm.baseUrl は必須です");
            }
            if (model == null || model.isBlank()) {
                throw new IllegalArgumentException("llm.model は必須です");
            }
            baseUrl = baseUrl.replaceAll("/+$", "");
            apiKey = apiKey == null ? "" : apiKey;
            temperature = temperature == null ? 0.2 : temperature;
            maxTokens = maxTokens == null ? 16384 : maxTokens;
            timeoutSeconds = timeoutSeconds == null ? 600 : timeoutSeconds;
            retryMaxAttempts = retryMaxAttempts == null ? 3 : retryMaxAttempts;
            retryBackoffMs = retryBackoffMs == null ? 2000 : retryBackoffMs;
        }
    }

    public record ReviewConfig(
            Integer maxDiffChars, Integer maxAdditionalFiles, Integer maxFileChars, Integer maxPasses
    ) {
        public ReviewConfig {
            maxDiffChars = maxDiffChars == null ? 200_000 : maxDiffChars;
            maxAdditionalFiles = maxAdditionalFiles == null ? 12 : maxAdditionalFiles;
            maxFileChars = maxFileChars == null ? 80_000 : maxFileChars;
            maxPasses = maxPasses == null ? 5 : maxPasses;
        }
    }

    public record StateConfig(String filePath, String mentionStateFilePath) {
        public StateConfig {
            filePath = (filePath == null || filePath.isBlank()) ? "./data/review-state.json" : filePath;
            mentionStateFilePath = (mentionStateFilePath == null || mentionStateFilePath.isBlank())
                    ? "./data/mention-state.json" : mentionStateFilePath;
        }
    }

    /** RAG(埋め込みベクトル検索によるコンテキスト拡張)設定。enabled=falseなら既存の申告制取得のみで動作する。 */
    public record RagConfig(
            Boolean enabled,
            String embeddingProvider,
            String embeddingBaseUrl,
            String embeddingModel,
            String embeddingApiKey,
            Integer topK,
            Double minScore,
            Integer chunkSize,
            Integer chunkOverlap,
            Integer maxIndexFiles,
            List<String> includeExtensions,
            String indexDir
    ) {
        public RagConfig {
            enabled = enabled == null ? Boolean.FALSE : enabled;
            embeddingProvider = (embeddingProvider == null || embeddingProvider.isBlank()) ? "ollama" : embeddingProvider;
            embeddingBaseUrl = (embeddingBaseUrl == null || embeddingBaseUrl.isBlank())
                    ? "http://localhost:11434" : embeddingBaseUrl.replaceAll("/+$", "");
            embeddingModel = (embeddingModel == null || embeddingModel.isBlank()) ? "nomic-embed-text" : embeddingModel;
            embeddingApiKey = embeddingApiKey == null ? "" : embeddingApiKey;
            topK = topK == null ? 10 : topK;
            minScore = minScore == null ? 0.65 : minScore;
            chunkSize = chunkSize == null ? 1000 : chunkSize;
            chunkOverlap = chunkOverlap == null ? 100 : chunkOverlap;
            maxIndexFiles = maxIndexFiles == null ? 10_000 : maxIndexFiles;
            includeExtensions = includeExtensions == null
                    ? List.of(".java", ".kt", ".ts", ".tsx", ".py", ".go", ".md")
                    : List.copyOf(includeExtensions);
            indexDir = (indexDir == null || indexDir.isBlank()) ? "./data/rag-index" : indexDir;
            if (!"ollama".equals(embeddingProvider) && !"openai-compatible".equals(embeddingProvider)) {
                throw new IllegalArgumentException(
                        "rag.embeddingProvider は ollama または openai-compatible を指定してください: " + embeddingProvider);
            }
        }
    }
}
