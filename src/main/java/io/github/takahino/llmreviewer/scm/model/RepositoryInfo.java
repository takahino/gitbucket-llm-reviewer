package io.github.takahino.llmreviewer.scm.model;

/** リポジトリ情報を表す汎用モデル(旧gitbucket.model.RepositoryDetail相当)。 */
public record RepositoryInfo(String name, String fullName, String defaultBranch) {
}
