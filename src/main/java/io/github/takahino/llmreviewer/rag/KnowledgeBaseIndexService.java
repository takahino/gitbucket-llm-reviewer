package io.github.takahino.llmreviewer.rag;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import io.github.takahino.llmreviewer.config.AppConfig;
import io.github.takahino.llmreviewer.git.RepositoryReader;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * {@code .review.yml} の {@code knowledgeBase} に指定されたコーディング規約文書を
 * langchain4j の {@link InMemoryEmbeddingStore} にインデックスし、内容のSHA-256ハッシュで
 * 変更検知して増分更新する(コードのheadShaと異なり、規約文書は変更頻度が低く追跡もdiff経由では
 * 行えないため、ファイル内容のハッシュ比較で変更判定する)。
 * 実際のロード・ingest・永続化は {@link EmbeddingStoreFileIndex} に委譲する。
 */
public class KnowledgeBaseIndexService {

    private static final Logger LOGGER = Logger.getLogger(KnowledgeBaseIndexService.class.getName());

    private final RepositoryReader repositoryReader;
    private final RagIndexStateStore stateStore;
    private final EmbeddingStoreFileIndex fileIndex;

    public KnowledgeBaseIndexService(
            RepositoryReader repositoryReader, EmbeddingModel embeddingModel,
            RagIndexStateStore stateStore, AppConfig.RagConfig ragConfig
    ) {
        this.repositoryReader = repositoryReader;
        this.stateStore = stateStore;
        this.fileIndex = new EmbeddingStoreFileIndex(embeddingModel, ragConfig, "knowledge-base-index.json");
    }

    /** 指定されたコーディング規約文書のインデックスを最新内容まで更新し、検索可能な状態にして返す。 */
    public InMemoryEmbeddingStore<TextSegment> ensureIndexed(
            String owner, String repo, String headSha, List<String> knowledgeBasePaths
    ) {
        InMemoryEmbeddingStore<TextSegment> store = fileIndex.storeFor(owner, repo);
        boolean changed = false;
        for (String path : knowledgeBasePaths) {
            changed |= reindexIfChanged(owner, repo, headSha, store, path);
        }
        if (changed) {
            fileIndex.persistStore(owner, repo, store);
        }
        return store;
    }

    private boolean reindexIfChanged(
            String owner, String repo, String headSha, InMemoryEmbeddingStore<TextSegment> store, String path
    ) {
        Optional<String> content = RepositoryReaders.readFileSafely(repositoryReader, owner, repo, headSha, path, LOGGER);
        if (content.isEmpty()) {
            return false;
        }
        String key = RagIndexStateStore.key(owner, repo, path);
        String hash = sha256(content.get());
        if (stateStore.getVersion(key).map(hash::equals).orElse(false)) {
            return false;
        }
        store.removeAll(MetadataFilterBuilder.metadataKey("path").isEqualTo(path));
        fileIndex.ingest(List.of(EmbeddingStoreFileIndex.toDocument(path, content.get())), store);
        stateStore.markVersion(key, hash);
        return true;
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
