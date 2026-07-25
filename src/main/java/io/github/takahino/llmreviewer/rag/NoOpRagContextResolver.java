package io.github.takahino.llmreviewer.rag;

import io.github.takahino.llmreviewer.config.RepoReviewConfig;
import io.github.takahino.llmreviewer.git.DiffResult;
import io.github.takahino.llmreviewer.scm.model.PullRequest;

import java.util.List;

/** rag.enabled=false時に使用する何もしない実装。既存の申告制取得のみで動作させる。 */
public class NoOpRagContextResolver implements RagContextResolver {

    @Override
    public RagSearchResult search(
            String owner, String repo, PullRequest pr, RepoReviewConfig repoConfig,
            DiffResult diff, List<String> changedFiles) {
        return RagSearchResult.empty();
    }
}
