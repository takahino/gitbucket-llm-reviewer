package io.github.takahino.llmreviewer.gitbucket;

import io.github.takahino.llmreviewer.config.AppConfig;
import io.github.takahino.llmreviewer.scm.GitRemoteLocator;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

/** GitBucketのgit smart HTTP URL規約(/git/{owner}/{repo}.git)と認証方式を提供する。 */
public class GitBucketRemoteLocator implements GitRemoteLocator {

    private final AppConfig.GitBucketConfig config;

    public GitBucketRemoteLocator(AppConfig.GitBucketConfig config) {
        this.config = config;
    }

    @Override
    public String remoteUrl(String owner, String repoName) {
        return config.baseUrl() + "/git/" + owner + "/" + repoName + ".git";
    }

    @Override
    public CredentialsProvider credentialsProvider() {
        // gitUsername/gitPassword が明示設定されていればそれを優先。
        // 未設定ならAPIトークンをBasic認証のusername/passwordとして試みる。
        if (!config.gitUsername().isBlank()) {
            return new UsernamePasswordCredentialsProvider(config.gitUsername(), config.gitPassword());
        }
        return new UsernamePasswordCredentialsProvider(config.token(), config.token());
    }
}
