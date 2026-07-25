package io.github.takahino.llmreviewer.rag;

import io.github.takahino.llmreviewer.config.RepoReviewConfig;
import io.github.takahino.llmreviewer.git.DiffResult;
import io.github.takahino.llmreviewer.gitbucket.model.PullRequestInfo;

/** diffの内容に関連するコード/コーディング規約をベクトル検索で解決する。 */
public interface RagContextResolver {

    RagSearchResult search(
            String owner, String repo, PullRequestInfo pr, RepoReviewConfig repoConfig, DiffResult diff);
}
