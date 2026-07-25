package io.github.takahino.llmreviewer.scm.model;

/** コミットに含まれるファイル変更を表す汎用モデル(旧gitbucket.model.CommitFileEntry相当)。 */
public record CommitFileChange(String filename, String status, String patch, Integer additions, Integer deletions) {
}
