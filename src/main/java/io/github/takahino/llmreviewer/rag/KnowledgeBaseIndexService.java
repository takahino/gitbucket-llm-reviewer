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
import io.github.takahino.llmreviewer.git.GitMirrorException;
import io.github.takahino.llmreviewer.git.RepositoryReader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@code .review.yml} の {@code knowledgeBase} に指定されたコーディング規約文書を
 * langchain4j の {@link InMemoryEmbeddingStore} にインデックスし、内容のSHA-256ハッシュで
 * 変更検知して増分更新する(コードのheadShaと異なり、規約文書は変更頻度が低く追跡もdiff経由では
 * 行えないため、ファイル内容のハッシュ比較で変更判定する)。
 */
public class KnowledgeBaseIndexService {

    private static final Logger LOGGER = Logger.getLogger(KnowledgeBaseIndexService.class.getName());

    private final RepositoryReader repositoryReader;
    private final EmbeddingModel embeddingModel;
    private final RagIndexStateStore stateStore;
    private final AppConfig.RagConfig ragConfig;
    private final Path indexDir;
    private final Map<String, InMemoryEmbeddingStore<TextSegment>> stores = new ConcurrentHashMap<>();

    public KnowledgeBaseIndexService(
            RepositoryReader repositoryReader, EmbeddingModel embeddingModel,
            RagIndexStateStore stateStore, AppConfig.RagConfig ragConfig
    ) {
        this.repositoryReader = repositoryReader;
        this.embeddingModel = embeddingModel;
        this.stateStore = stateStore;
        this.ragConfig = ragConfig;
        this.indexDir = Path.of(ragConfig.indexDir());
    }

    /** 指定されたコーディング規約文書のインデックスを最新内容まで更新し、検索可能な状態にして返す。 */
    public InMemoryEmbeddingStore<TextSegment> ensureIndexed(
            String owner, String repo, String headSha, List<String> knowledgeBasePaths
    ) {
        InMemoryEmbeddingStore<TextSegment> store = storeFor(owner, repo);
        boolean changed = false;
        for (String path : knowledgeBasePaths) {
            changed |= reindexIfChanged(owner, repo, headSha, store, path);
        }
        if (changed) {
            persistStore(owner, repo, store);
        }
        return store;
    }

    private boolean reindexIfChanged(
            String owner, String repo, String headSha, InMemoryEmbeddingStore<TextSegment> store, String path
    ) {
        Optional<String> content = readFileSafely(owner, repo, headSha, path);
        if (content.isEmpty()) {
            return false;
        }
        String key = RagIndexStateStore.key(owner, repo, path);
        String hash = sha256(content.get());
        if (stateStore.getVersion(key).map(hash::equals).orElse(false)) {
            return false;
        }
        store.removeAll(MetadataFilterBuilder.metadataKey("path").isEqualTo(path));
        ingest(List.of(toDocument(path, content.get())), store);
        stateStore.markVersion(key, hash);
        return true;
    }

    private Optional<String> readFileSafely(String owner, String repo, String headSha, String path) {
        try {
            return repositoryReader.readFile(owner, repo, headSha, path);
        } catch (GitMirrorException e) {
            LOGGER.log(Level.WARNING, "コーディング規約文書の読み込みに失敗したためスキップします: " + path, e);
            return Optional.empty();
        }
    }

    private void ingest(List<Document> documents, InMemoryEmbeddingStore<TextSegment> store) {
        EmbeddingStoreIngestor ingestor = EmbeddingStoreIngestor.builder()
                .documentSplitter(DocumentSplitters.recursive(ragConfig.chunkSize(), ragConfig.chunkOverlap()))
                .embeddingModel(embeddingModel)
                .embeddingStore(store)
                .build();
        ingestor.ingest(documents);
    }

    private Document toDocument(String path, String content) {
        return Document.from(content, new Metadata().put("path", path));
    }

    private InMemoryEmbeddingStore<TextSegment> storeFor(String owner, String repo) {
        return stores.computeIfAbsent(RagIndexStateStore.key(owner, repo), k -> {
            Path file = indexFilePath(owner, repo);
            if (Files.isRegularFile(file)) {
                try {
                    return InMemoryEmbeddingStore.fromFile(file);
                } catch (RuntimeException e) {
                    LOGGER.log(Level.WARNING,
                            "コーディング規約インデックスファイルの読み込みに失敗したため、空のインデックスで再構築します: " + file, e);
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
            LOGGER.log(Level.WARNING, "コーディング規約インデックスの永続化に失敗しました: " + file, e);
        }
    }

    private Path indexFilePath(String owner, String repo) {
        return indexDir.resolve(owner).resolve(repo).resolve("knowledge-base-index.json");
    }

    private static String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256アルゴリズムが利用できません", e);
        }
    }
}
