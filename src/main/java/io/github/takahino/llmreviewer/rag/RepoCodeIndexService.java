package io.github.takahino.llmreviewer.rag;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import io.github.takahino.llmreviewer.config.AppConfig;
import io.github.takahino.llmreviewer.git.DiffResult;
import io.github.takahino.llmreviewer.git.GitMirrorException;
import io.github.takahino.llmreviewer.git.JGitDiffProvider;
import io.github.takahino.llmreviewer.git.UnifiedDiffIndex;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * リポジトリコード全体を langchain4j の {@link InMemoryEmbeddingStore} にインデックスし、
 * 増分更新(前回インデックス済みheadShaからの差分ファイルのみ再embed)を行う。
 * インデックスはリポジトリ毎に {@code rag.indexDir}/owner/repo/code-index.json へファイル永続化する。
 */
public class RepoCodeIndexService {

    private static final Logger LOGGER = Logger.getLogger(RepoCodeIndexService.class.getName());

    private final JGitDiffProvider jGitProvider;
    private final EmbeddingModel embeddingModel;
    private final RagIndexStateStore stateStore;
    private final AppConfig.RagConfig ragConfig;
    private final Path indexDir;
    private final Map<String, InMemoryEmbeddingStore<TextSegment>> stores = new ConcurrentHashMap<>();

    public RepoCodeIndexService(
            JGitDiffProvider jGitProvider, EmbeddingModel embeddingModel,
            RagIndexStateStore stateStore, AppConfig.RagConfig ragConfig
    ) {
        this.jGitProvider = jGitProvider;
        this.embeddingModel = embeddingModel;
        this.stateStore = stateStore;
        this.ragConfig = ragConfig;
        this.indexDir = Path.of(ragConfig.indexDir());
    }

    /** リポジトリコードのインデックスを最新headShaまで更新し、検索可能な状態にして返す。 */
    public InMemoryEmbeddingStore<TextSegment> ensureIndexed(String owner, String repo, String headSha) {
        String key = RagIndexStateStore.key(owner, repo);
        InMemoryEmbeddingStore<TextSegment> store = storeFor(owner, repo);
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
            persistStore(owner, repo, store);
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
            readFileSafely(owner, repo, headSha, path).ifPresent(content -> documents.add(toDocument(path, content)));
        }
        ingest(documents, store);
    }

    private void incrementalReindex(
            String owner, String repo, InMemoryEmbeddingStore<TextSegment> store,
            String previousHeadSha, String headSha
    ) {
        DiffResult diff = jGitProvider.getIncrementalDiff(owner, repo, previousHeadSha, headSha, List.of(), Integer.MAX_VALUE);
        List<String> changedFiles = UnifiedDiffIndex.parse(diff.diffText()).changedFiles();
        List<Document> toReindex = new ArrayList<>();
        for (String path : changedFiles) {
            if (!isIncluded(path)) {
                continue;
            }
            removeFromStore(store, path);
            readFileSafely(owner, repo, headSha, path).ifPresent(content -> toReindex.add(toDocument(path, content)));
        }
        ingest(toReindex, store);
    }

    private Optional<String> readFileSafely(String owner, String repo, String headSha, String path) {
        try {
            return jGitProvider.readFile(owner, repo, headSha, path);
        } catch (GitMirrorException e) {
            LOGGER.log(Level.FINE, "RAGインデックス対象ファイルの読み込みに失敗したためスキップします: " + path, e);
            return Optional.empty();
        }
    }

    private void ingest(List<Document> documents, InMemoryEmbeddingStore<TextSegment> store) {
        if (documents.isEmpty()) {
            return;
        }
        EmbeddingStoreIngestor ingestor = EmbeddingStoreIngestor.builder()
                .documentSplitter(DocumentSplitters.recursive(ragConfig.chunkSize(), ragConfig.chunkOverlap()))
                .embeddingModel(embeddingModel)
                .embeddingStore(store)
                .build();
        ingestor.ingest(documents);
    }

    private void removeFromStore(InMemoryEmbeddingStore<TextSegment> store, String path) {
        store.removeAll(MetadataFilterBuilder.metadataKey("path").isEqualTo(path));
    }

    private Document toDocument(String path, String content) {
        return Document.from(content, new Metadata().put("path", path));
    }

    private boolean isIncluded(String path) {
        return ragConfig.includeExtensions().stream().anyMatch(path::endsWith);
    }

    private InMemoryEmbeddingStore<TextSegment> storeFor(String owner, String repo) {
        return stores.computeIfAbsent(RagIndexStateStore.key(owner, repo), k -> {
            Path file = indexFilePath(owner, repo);
            if (Files.isRegularFile(file)) {
                try {
                    return InMemoryEmbeddingStore.fromFile(file);
                } catch (RuntimeException e) {
                    LOGGER.log(Level.WARNING,
                            "RAGインデックスファイルの読み込みに失敗したため、空のインデックスで再構築します: " + file, e);
                }
            }
            return new InMemoryEmbeddingStore<>();
        });
    }

    private void persistStore(String owner, String repo, InMemoryEmbeddingStore<TextSegment> store) {
        Path file = indexFilePath(owner, repo);
        try {
            Files.createDirectories(file.getParent());
            store.serializeToFile(file);
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.WARNING, "RAGインデックスの永続化に失敗しました: " + file, e);
        }
    }

    private Path indexFilePath(String owner, String repo) {
        return indexDir.resolve(owner).resolve(repo).resolve("code-index.json");
    }
}
