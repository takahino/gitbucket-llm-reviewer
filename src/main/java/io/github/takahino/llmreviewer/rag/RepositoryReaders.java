package io.github.takahino.llmreviewer.rag;

import io.github.takahino.llmreviewer.git.GitMirrorException;
import io.github.takahino.llmreviewer.git.RepositoryReader;

import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/** {@link RepositoryReader#readFile} を例外握りつぶし付きで呼ぶ共通ヘルパー(RAGインデックス構築で使用)。 */
final class RepositoryReaders {

    private RepositoryReaders() {
    }

    static Optional<String> readFileSafely(
            RepositoryReader reader, String owner, String repo, String ref, String path, Logger logger) {
        try {
            return reader.readFile(owner, repo, ref, path);
        } catch (GitMirrorException e) {
            logger.log(Level.FINE, "RAGインデックス対象ファイルの読み込みに失敗したためスキップします: " + path, e);
            return Optional.empty();
        }
    }
}
