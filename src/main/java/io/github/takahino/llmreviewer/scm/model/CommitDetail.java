package io.github.takahino.llmreviewer.scm.model;

import java.util.List;

/** コミット詳細(変更ファイル一覧)を表す汎用モデル。 */
public record CommitDetail(String sha, List<CommitFileChange> files) {
    public CommitDetail {
        files = files == null ? List.of() : List.copyOf(files);
    }
}
