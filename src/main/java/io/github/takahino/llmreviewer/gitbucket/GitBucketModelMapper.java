package io.github.takahino.llmreviewer.gitbucket;

import io.github.takahino.llmreviewer.gitbucket.model.BranchRef;
import io.github.takahino.llmreviewer.gitbucket.model.CommitDetail;
import io.github.takahino.llmreviewer.gitbucket.model.CommitFileEntry;
import io.github.takahino.llmreviewer.gitbucket.model.CommitRef;
import io.github.takahino.llmreviewer.gitbucket.model.GitUser;
import io.github.takahino.llmreviewer.gitbucket.model.IssueComment;
import io.github.takahino.llmreviewer.gitbucket.model.PullRequestInfo;
import io.github.takahino.llmreviewer.gitbucket.model.RepositoryDetail;
import io.github.takahino.llmreviewer.scm.model.Account;
import io.github.takahino.llmreviewer.scm.model.CommitFileChange;
import io.github.takahino.llmreviewer.scm.model.GitRef;
import io.github.takahino.llmreviewer.scm.model.PullRequest;
import io.github.takahino.llmreviewer.scm.model.RepositoryInfo;

/** GitBucketのwire DTO(gitbucket.model.*)を汎用モデル(scm.model.*)へ変換する。 */
final class GitBucketModelMapper {

    private GitBucketModelMapper() {
    }

    static PullRequest toPullRequest(PullRequestInfo src) {
        return new PullRequest(src.number(), src.title(), src.body(), src.state(),
                toAccount(src.user()), toGitRef(src.head()), toGitRef(src.base()), src.updatedAt(), src.htmlUrl());
    }

    static GitRef toGitRef(BranchRef src) {
        return src == null ? null : new GitRef(src.ref(), src.sha());
    }

    static Account toAccount(GitUser src) {
        return src == null ? null : new Account(src.login());
    }

    static io.github.takahino.llmreviewer.scm.model.IssueComment toIssueComment(IssueComment src) {
        return new io.github.takahino.llmreviewer.scm.model.IssueComment(src.id(), src.body(), toAccount(src.user()));
    }

    static RepositoryInfo toRepositoryInfo(RepositoryDetail src) {
        return new RepositoryInfo(src.name(), src.fullName(), src.defaultBranch());
    }

    static io.github.takahino.llmreviewer.scm.model.CommitRef toCommitRef(CommitRef src) {
        return new io.github.takahino.llmreviewer.scm.model.CommitRef(src.sha());
    }

    static io.github.takahino.llmreviewer.scm.model.CommitDetail toCommitDetail(CommitDetail src) {
        return new io.github.takahino.llmreviewer.scm.model.CommitDetail(src.sha(),
                src.files().stream().map(GitBucketModelMapper::toCommitFileChange).toList());
    }

    static CommitFileChange toCommitFileChange(CommitFileEntry src) {
        return new CommitFileChange(src.filename(), src.status(), src.patch(), src.additions(), src.deletions());
    }
}
