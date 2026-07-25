package io.github.takahino.llmreviewer.scm;

import io.github.takahino.llmreviewer.scm.model.Account;
import io.github.takahino.llmreviewer.scm.model.CommitDetail;
import io.github.takahino.llmreviewer.scm.model.CommitRef;
import io.github.takahino.llmreviewer.scm.model.IssueComment;
import io.github.takahino.llmreviewer.scm.model.PullRequest;
import io.github.takahino.llmreviewer.scm.model.RepositoryInfo;

import java.util.List;
import java.util.Optional;

/** Gitホスティングサービス(GitBucket/GitHub/GitLab/Bitbucket等)のREST API操作を抽象化する。現状の実装はGitBucketClientのみ。 */
public interface ScmClient {

    List<PullRequest> listOpenPullRequests(String owner, String repo);

    PullRequest getPullRequest(String owner, String repo, int number);

    List<CommitRef> listPullRequestCommits(String owner, String repo, int number);

    CommitDetail getCommitDetail(String owner, String repo, String sha);

    /** トークンに紐づく認証済みユーザー情報を取得する。メンション応答機能でBot自身のユーザー名を解決するために使用する。 */
    Account getAuthenticatedUser();

    /** リポジトリ情報(デフォルトブランチ等)を取得する。 */
    RepositoryInfo getRepository(String owner, String repo);

    /** 指定refのファイルの生の中身を取得する。存在しない場合は空を返す。 */
    Optional<String> getRawContent(String owner, String repo, String path, String ref);

    void postIssueComment(String owner, String repo, int issueNumber, String commentBody);

    /** PRの過去コメントを折りたたむため、Issueコメント一覧を取得する。 */
    List<IssueComment> listIssueComments(String owner, String repo, int issueNumber);

    /** 既存のIssueコメント本文を更新する(過去のレビューコメントを折りたたむために使用)。 */
    void updateIssueComment(String owner, String repo, long commentId, String newBody);
}
