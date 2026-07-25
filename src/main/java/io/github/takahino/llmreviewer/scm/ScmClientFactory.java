package io.github.takahino.llmreviewer.scm;

import io.github.takahino.llmreviewer.config.AppConfig;
import io.github.takahino.llmreviewer.gitbucket.GitBucketClient;
import io.github.takahino.llmreviewer.gitbucket.GitBucketRemoteLocator;

/** config.yml の provider 設定に応じて {@link ScmClient}/{@link GitRemoteLocator} の実装を組み立てる。 */
public final class ScmClientFactory {

    private ScmClientFactory() {
    }

    public record ScmBundle(ScmClient client, GitRemoteLocator remoteLocator) {
    }

    public static ScmBundle create(AppConfig config) {
        GitProviderType type = GitProviderType.fromConfigValue(config.provider());
        return switch (type) {
            case GITBUCKET -> new ScmBundle(
                    new GitBucketClient(config.gitbucket()),
                    new GitBucketRemoteLocator(config.gitbucket()));
            case GITHUB, GITLAB, BITBUCKET -> throw new UnsupportedOperationException(
                    "provider=%s はまだ実装されていません。現時点でサポートされているのは gitbucket のみです。"
                            .formatted(type.name().toLowerCase(java.util.Locale.ROOT)));
        };
    }
}
