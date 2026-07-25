package io.github.takahino.llmreviewer.rag;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * RAGインデックスの版識別子(key → headSha または コンテンツハッシュ)をJSONファイルに永続化する
 * 汎用ストア({@link io.github.takahino.llmreviewer.review.ReviewStateStore}と同型パターン)。
 * 単一インスタンスを {@link RepoCodeIndexService}(key=owner/repo, value=indexedHeadSha)と
 * {@link KnowledgeBaseIndexService}(key=owner/repo:path, value=contentHash)の双方に共有注入し、
 * キー空間の違いで衝突なく使い分ける({@code key(owner, repo)} と {@code key(owner, repo, path)} を参照)。
 */
public class RagIndexStateStore {

    private static final Logger LOGGER = Logger.getLogger(RagIndexStateStore.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path filePath;
    private final Map<String, String> versions;

    public RagIndexStateStore(Path filePath) {
        this.filePath = filePath;
        this.versions = new ConcurrentHashMap<>(load(filePath));
    }

    public static String key(String owner, String repo) {
        return owner + "/" + repo;
    }

    public static String key(String owner, String repo, String path) {
        return owner + "/" + repo + ":" + path;
    }

    public synchronized Optional<String> getVersion(String key) {
        return Optional.ofNullable(versions.get(key));
    }

    public synchronized void markVersion(String key, String version) {
        versions.put(key, version);
        persist();
    }

    private static Map<String, String> load(Path filePath) {
        if (!Files.isRegularFile(filePath)) {
            return new ConcurrentHashMap<>();
        }
        try {
            return MAPPER.readValue(filePath.toFile(),
                    MAPPER.getTypeFactory().constructMapType(ConcurrentHashMap.class, String.class, String.class));
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "RAGインデックス状態ファイルの読み込みに失敗したため、空の状態で開始します: " + filePath, e);
            return new ConcurrentHashMap<>();
        }
    }

    private void persist() {
        try {
            Path parent = filePath.toAbsolutePath().getParent();
            Files.createDirectories(parent);
            Path tmp = Files.createTempFile(parent, "rag-index-state", ".tmp");
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), versions);
            Files.move(tmp, filePath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("RAGインデックス状態の永続化に失敗しました: " + filePath, e);
        }
    }
}
