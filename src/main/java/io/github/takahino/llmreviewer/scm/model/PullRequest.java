package io.github.takahino.llmreviewer.scm.model;

/** プルリクエストを表す汎用モデル(旧gitbucket.model.PullRequestInfo相当)。 */
public record PullRequest(
        int number,
        String title,
        String body,
        String state,
        Account user,
        GitRef head,
        GitRef base,
        String updatedAt,
        String htmlUrl
) {
}
