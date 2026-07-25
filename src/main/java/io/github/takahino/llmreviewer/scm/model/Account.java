package io.github.takahino.llmreviewer.scm.model;

/** PR作成者・コメント作成者などプロバイダ横断の「ユーザー」を表す汎用モデル(旧gitbucket.model.GitUser相当)。 */
public record Account(String login) {
}
