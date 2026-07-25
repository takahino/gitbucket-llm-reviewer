package io.github.takahino.llmreviewer.rag;

import io.github.takahino.llmreviewer.config.RepoReviewConfig;
import io.github.takahino.llmreviewer.git.DiffResult;
import io.github.takahino.llmreviewer.scm.model.PullRequest;

import java.util.List;

/** diffの内容に関連するコード/コーディング規約をベクトル検索で解決する。 */
public interface RagContextResolver {

    /**
     * @param changedFiles diffの変更ファイル一覧(呼び出し元で {@code UnifiedDiffIndex.parse(diff.diffText())}
     *                      から既に計算済みのものを渡す。ここで再パースしない)
     */
    RagSearchResult search(
            String owner, String repo, PullRequest pr, RepoReviewConfig repoConfig,
            DiffResult diff, List<String> changedFiles);
}
