package io.github.takahino.llmreviewer.rag;

/** ベクトル検索で取得したコード/規約文書の1チャンク。 */
public record RetrievedChunk(String sourcePath, String content, double score) {
}
