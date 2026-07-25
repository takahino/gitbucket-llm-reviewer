package io.github.takahino.llmreviewer.rag;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import io.github.takahino.llmreviewer.config.AppConfig;
import io.github.takahino.llmreviewer.git.GitMirrorException;
import io.github.takahino.llmreviewer.git.JGitDiffProvider;
import io.github.takahino.llmreviewer.git.UnifiedDiffIndex;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * リポジトリコード全体を langchain4j の {@link InMemoryEmbeddingStore} にインデックスし、
 * 増分更新(前回インデックス済みheadShaからの差分ファイルのみ再embed)を行う。
 * インデックスはリポジトリ毎に {@code rag.indexDir}/owner/repo/code-index.json へファイル永続化する
 * (実際のロード・ingest・永続化は {@link EmbeddingStoreFileIndex} に委譲)。
 */
public class RepoCodeIndexService {

    private static final Logger LOGGER = Logger.getLogger(RepoCodeIndexService.class.getName());

    private final JGitDiffProvider jGitProvider;
    private final RagIndexStateStore stateStore;
    private final AppConfig.RagConfig ragConfig;
    private final EmbeddingStoreFileIndex fileIndex;

    public RepoCodeIndexService(
            JGitDiffProvider jGitProvider, EmbeddingModel embeddingModel,
            RagIndexStateStore stateStore, AppConfig.RagConfig ragConfig
    ) {
        this.jGitProvider = jGitProvider;
        this.stateStore = stateStore;
        this.ragConfig = ragConfig;
        this.fileIndex = new EmbeddingStoreFileIndex(embeddingModel, ragConfig, "code-index.json");
    }

    /** リポジトリコードのインデックスを最新headShaまで更新し、検索可能な状態にして返す。 */
    public InMemoryEmbeddingStore<TextSegment> ensureIndexed(String owner, String repo, String headSha) {
        String key = RagIndexStateStore.key(owner, repo);
        InMemoryEmbeddingStore<TextSegment> store = fileIndex.storeFor(owner, repo);
        Optional<String> indexedHeadSha = stateStore.getVersion(key);

        if (indexedHeadSha.isPresent() && indexedHeadSha.get().equals(headSha)) {
            return store;
        }

        try {
            if (indexedHeadSha.isPresent()) {
                incrementalReindex(owner, repo, store, indexedHeadSha.get(), headSha);
            } else {
                fullReindex(owner, repo, store, headSha);
            }
            stateStore.markVersion(key, headSha);
            fileIndex.persistStore(owner, repo, store);
        } catch (GitMirrorException e) {
            LOGGER.log(Level.WARNING,
                    "RAGインデックス更新に失敗したため、既存インデックスのまま継続します: %s/%s".formatted(owner, repo), e);
        }
        return store;
    }

    private void fullReindex(String owner, String repo, InMemoryEmbeddingStore<TextSegment> store, String headSha) {
        List<String> files = jGitProvider.listFiles(owner, repo, headSha, ragConfig.maxIndexFiles());
        List<Document> documents = new ArrayList<>();
        for (String path : files) {
            if (!isIncluded(path)) {
                continue;
            }
            readFileSafely(owner, repo, headSha, path)
                    .ifPresent(content -> documents.add(EmbeddingStoreFileIndex.toDocument(path, content)));
        }
        fileIndex.ingest(documents, store);
    }

    private void incrementalReindex(
            String owner, String repo, InMemoryEmbeddingStore<TextSegment> store,
            String previousHeadSha, String headSha
    ) {
        String diff = jGitProvider.getIncrementalDiff(owner, repo, previousHeadSha, headSha, List.of());
        List<String> changedFiles = UnifiedDiffIndex.parse(diff).changedFiles();
        List<Document> toReindex = new ArrayList<>();
        for (String path : changedFiles) {
            if (!isIncluded(path)) {
                continue;
            }
            store.removeAll(MetadataFilterBuilder.metadataKey("path").isEqualTo(path));
            readFileSafely(owner, repo, headSha, path)
                    .ifPresent(content -> toReindex.add(EmbeddingStoreFileIndex.toDocument(path, content)));
        }
        fileIndex.ingest(toReindex, store);
    }

    private Optional<String> readFileSafely(String owner, String repo, String headSha, String path) {
        return RepositoryReaders.readFileSafely(jGitProvider, owner, repo, headSha, path, LOGGER);
    }

    private boolean isIncluded(String path) {
        return ragConfig.includeExtensions().stream().anyMatch(path::endsWith);
    }
}
