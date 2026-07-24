package io.github.takahino.llmreviewer.config;

import java.util.List;
import java.util.Objects;

/**
 * ツール側設定(config.yml)。各ネストレコードのコンパクトコンストラクタで
 * 必須項目の検証とデフォルト値の適用を行う(設定ファイルはシステム境界のため)。
 */
public record AppConfig(
        GitBucketConfig gitbucket,
        List<RepositoryRef> repositories,
        PollingConfig polling,
        LlmConfig llm,
        ReviewConfig review,
        StateConfig state,
        String workDir
) {
    public AppConfig {
        Objects.requireNonNull(gitbucket, "gitbucket 設定は必須です");
        if (repositories == null || repositories.isEmpty()) {
            throw new IllegalArgumentException("repositories には最低1件のリポジトリを指定してください");
        }
        repositories = List.copyOf(repositories);
        Objects.requireNonNull(llm, "llm 設定は必須です");
        polling = polling != null ? polling : new PollingConfig(null);
        review = review != null ? review : new ReviewConfig(null, null, null, null);
        state = state != null ? state : new StateConfig(null);
        workDir = (workDir == null || workDir.isBlank()) ? "./data/repos" : workDir;
    }

    public record GitBucketConfig(String baseUrl, String token, String gitUsername, String gitPassword) {
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
            Integer timeoutSeconds
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
            maxTokens = maxTokens == null ? 4096 : maxTokens;
            timeoutSeconds = timeoutSeconds == null ? 300 : timeoutSeconds;
        }
    }

    public record ReviewConfig(Integer maxDiffChars, Integer maxAdditionalFiles, Integer maxFileChars, Integer maxPasses) {
        public ReviewConfig {
            maxDiffChars = maxDiffChars == null ? 60_000 : maxDiffChars;
            maxAdditionalFiles = maxAdditionalFiles == null ? 5 : maxAdditionalFiles;
            maxFileChars = maxFileChars == null ? 50_000 : maxFileChars;
            maxPasses = maxPasses == null ? 3 : maxPasses;
        }
    }

    public record StateConfig(String filePath) {
        public StateConfig {
            filePath = (filePath == null || filePath.isBlank()) ? "./data/review-state.json" : filePath;
        }
    }
}
