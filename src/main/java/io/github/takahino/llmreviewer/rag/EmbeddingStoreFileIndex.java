package io.github.takahino.llmreviewer.rag;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import io.github.takahino.llmreviewer.config.AppConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * リポジトリ毎に {@link InMemoryEmbeddingStore} をファイルへ永続化しつつインスタンスをキャッシュする、
 * {@link RepoCodeIndexService}/{@link KnowledgeBaseIndexService} 共通の基盤。
 * ロード・ingest・永続化のみを担い、「何をいつ再インデックスするか」の判定は呼び出し側に委ねる。
 */
final class EmbeddingStoreFileIndex {

    private static final Logger LOGGER = Logger.getLogger(EmbeddingStoreFileIndex.class.getName());

    private final EmbeddingModel embeddingModel;
    private final AppConfig.RagConfig ragConfig;
    private final Path indexDir;
    private final String indexFileName;
    private final Map<String, InMemoryEmbeddingStore<TextSegment>> stores = new ConcurrentHashMap<>();

    EmbeddingStoreFileIndex(EmbeddingModel embeddingModel, AppConfig.RagConfig ragConfig, String indexFileName) {
        this.embeddingModel = embeddingModel;
        this.ragConfig = ragConfig;
        this.indexDir = Path.of(ragConfig.indexDir());
        this.indexFileName = indexFileName;
    }

    InMemoryEmbeddingStore<TextSegment> storeFor(String owner, String repo) {
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

    void ingest(List<Document> documents, InMemoryEmbeddingStore<TextSegment> store) {
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

    void persistStore(String owner, String repo, InMemoryEmbeddingStore<TextSegment> store) {
        Path file = indexFilePath(owner, repo);
        try {
            Files.createDirectories(file.getParent());
            store.serializeToFile(file);
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.WARNING, "RAGインデックスの永続化に失敗しました: " + file, e);
        }
    }

    private Path indexFilePath(String owner, String repo) {
        return indexDir.resolve(owner).resolve(repo).resolve(indexFileName);
    }

    static Document toDocument(String path, String content) {
        return Document.from(content, new Metadata().put("path", path));
    }
}
