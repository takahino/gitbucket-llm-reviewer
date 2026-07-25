package io.github.takahino.llmreviewer.rag;

import java.util.List;

/** RAG検索結果。関連コード候補と関連コーディング規約抜粋を分けて保持する。 */
public record RagSearchResult(List<RetrievedChunk> relatedCode, List<RetrievedChunk> knowledgeBase) {
    public RagSearchResult {
        relatedCode = List.copyOf(relatedCode);
        knowledgeBase = List.copyOf(knowledgeBase);
    }

    public static RagSearchResult empty() {
        return new RagSearchResult(List.of(), List.of());
    }

    public boolean isEmpty() {
        return relatedCode.isEmpty() && knowledgeBase.isEmpty();
    }
}
