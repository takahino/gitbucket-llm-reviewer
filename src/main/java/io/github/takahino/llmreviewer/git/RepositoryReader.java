package io.github.takahino.llmreviewer.git;

import java.util.List;
import java.util.Optional;

/** 全体整合チェック(Nパス)のために、リポジトリのファイルツリー一覧・任意ファイル内容を取得する。 */
public interface RepositoryReader {
    List<String> listFiles(String owner, String repo, String ref, int maxFiles);

    Optional<String> readFile(String owner, String repo, String ref, String path);
}
