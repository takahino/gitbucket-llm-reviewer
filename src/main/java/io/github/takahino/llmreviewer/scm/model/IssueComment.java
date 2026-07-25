package io.github.takahino.llmreviewer.scm.model;

/** Issue/PRコメントを表す汎用モデル。 */
public record IssueComment(long id, String body, Account user) {
}
