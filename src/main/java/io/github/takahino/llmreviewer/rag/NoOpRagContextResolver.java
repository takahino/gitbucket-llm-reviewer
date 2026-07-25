package io.github.takahino.llmreviewer.rag;

import io.github.takahino.llmreviewer.config.RepoReviewConfig;
import io.github.takahino.llmreviewer.git.DiffResult;
import io.github.takahino.llmreviewer.gitbucket.model.PullRequestInfo;

/** rag.enabled=false時に使用する何もしない実装。既存の申告制取得のみで動作させる。 */
public class NoOpRagContextResolver implements RagContextResolver {

    @Override
    public RagSearchResult search(
            String owner, String repo, PullRequestInfo pr, RepoReviewConfig repoConfig, DiffResult diff) {
        return RagSearchResult.empty();
    }
}
