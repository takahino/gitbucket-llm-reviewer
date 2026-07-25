package io.github.takahino.llmreviewer.scm.model;

/** PRのhead/baseを表す汎用モデル(旧gitbucket.model.BranchRef相当)。 */
public record GitRef(String ref, String sha) {
}
