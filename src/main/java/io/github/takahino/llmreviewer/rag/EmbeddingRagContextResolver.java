package io.github.takahino.llmreviewer.rag;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.ContentMetadata;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import io.github.takahino.llmreviewer.config.AppConfig;
import io.github.takahino.llmreviewer.config.RepoReviewConfig;
import io.github.takahino.llmreviewer.git.DiffResult;
import io.github.takahino.llmreviewer.git.UnifiedDiffIndex;
import io.github.takahino.llmreviewer.gitbucket.model.PullRequestInfo;

import java.util.List;

/**
 * ベクトル検索によるRAGコンテキスト解決。diffのテキストをクエリとして、
 * リポジトリコード全体と(モノレポのパス毎観点解決を経て絞り込んだ)コーディング規約文書の
 * 双方から関連チャンクを検索し、「参考情報」として提示する
 * (既存のLLM申告制オンデマンド取得 {@code need_more_context} を置き換えるものではなく補完する)。
 */
public class EmbeddingRagContextResolver implements RagContextResolver {

    /** embeddingモデルの入力トークン上限を超えないよう、クエリに使うdiffの文字数を制限する。 */
    private static final int MAX_QUERY_CHARS = 4000;

    private final RepoCodeIndexService codeIndexService;
    private final KnowledgeBaseIndexService knowledgeBaseIndexService;
    private final EmbeddingModel embeddingModel;
    private final AppConfig.RagConfig ragConfig;

    public EmbeddingRagContextResolver(
            RepoCodeIndexService codeIndexService,
            KnowledgeBaseIndexService knowledgeBaseIndexService,
            EmbeddingModel embeddingModel,
            AppConfig.RagConfig ragConfig
    ) {
        this.codeIndexService = codeIndexService;
        this.knowledgeBaseIndexService = knowledgeBaseIndexService;
        this.embeddingModel = embeddingModel;
        this.ragConfig = ragConfig;
    }

    @Override
    public RagSearchResult search(
            String owner, String repo, PullRequestInfo pr, RepoReviewConfig repoConfig, DiffResult diff) {
        String queryText = truncate(diff.diffText(), MAX_QUERY_CHARS);
        if (queryText.isBlank()) {
            return RagSearchResult.empty();
        }

        List<RetrievedChunk> relatedCode = searchStore(
                codeIndexService.ensureIndexed(owner, repo, pr.head().sha()), queryText);

        List<String> changedFiles = UnifiedDiffIndex.parse(diff.diffText()).changedFiles();
        List<String> knowledgeBasePaths = repoConfig.resolveKnowledgeBaseFor(changedFiles);
        List<RetrievedChunk> knowledgeBase = knowledgeBasePaths.isEmpty()
                ? List.of()
                : searchStore(
                        knowledgeBaseIndexService.ensureIndexed(owner, repo, pr.head().sha(), knowledgeBasePaths),
                        queryText);

        return new RagSearchResult(relatedCode, knowledgeBase);
    }

    private List<RetrievedChunk> searchStore(InMemoryEmbeddingStore<TextSegment> store, String queryText) {
        EmbeddingStoreContentRetriever retriever = EmbeddingStoreContentRetriever.builder()
                .embeddingStore(store)
                .embeddingModel(embeddingModel)
                .maxResults(ragConfig.topK())
                .minScore(ragConfig.minScore())
                .build();
        return retriever.retrieve(Query.from(queryText)).stream().map(this::toChunk).toList();
    }

    private RetrievedChunk toChunk(Content content) {
        String path = content.textSegment().metadata().getString("path");
        double score = content.metadata().get(ContentMetadata.SCORE) instanceof Double d ? d : 0.0;
        return new RetrievedChunk(path == null ? "(不明)" : path, content.textSegment().text(), score);
    }

    private static String truncate(String text, int maxChars) {
        return text.length() <= maxChars ? text : text.substring(0, maxChars);
    }
}
