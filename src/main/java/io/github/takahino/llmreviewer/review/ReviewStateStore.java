package io.github.takahino.llmreviewer.review;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/** レビュー済み状態(PR毎の最終レビューheadSha)をJSONファイルに永続化し、二重レビューを防止する。 */
public class ReviewStateStore {

    private static final Logger LOGGER = Logger.getLogger(ReviewStateStore.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public record StateEntry(String reviewedHeadSha, String reviewedAt, String status, int failureCount) {
    }

    private final Path filePath;
    private final Map<String, StateEntry> state;

    public ReviewStateStore(Path filePath) {
        this.filePath = filePath;
        this.state = new ConcurrentHashMap<>(load(filePath));
    }

    public static String key(String owner, String repo, int prNumber) {
        return "%s/%s#%d".formatted(owner, repo, prNumber);
    }

    /** 前回のレビュー状態を取得する(増分レビューの基準headSha判定に使用)。未レビューならempty。 */
    public synchronized Optional<StateEntry> get(String key) {
        return Optional.ofNullable(state.get(key));
    }

    /** 未レビューの新規PR、または push によりheadShaが変わった場合、あるいは前回失敗(未skip)の場合に true。 */
    public synchronized boolean needsReview(String key, String currentHeadSha) {
        StateEntry entry = state.get(key);
        if (entry == null) {
            return true;
        }
        if (!entry.reviewedHeadSha().equals(currentHeadSha)) {
            return true;
        }
        return "failed".equals(entry.status());
    }

    public synchronized void markReviewed(String key, String headSha) {
        state.put(key, new StateEntry(headSha, Instant.now().toString(), "reviewed", 0));
        persist();
    }

    public synchronized void markFailed(String key, String headSha, int maxFailures) {
        StateEntry previous = state.get(key);
        int failureCount = (previous != null && previous.reviewedHeadSha().equals(headSha)) ? previous.failureCount() + 1 : 1;
        String status = failureCount >= maxFailures ? "skipped" : "failed";
        state.put(key, new StateEntry(headSha, Instant.now().toString(), status, failureCount));
        persist();
    }

    private static Map<String, StateEntry> load(Path filePath) {
        if (!Files.isRegularFile(filePath)) {
            return new ConcurrentHashMap<>();
        }
        try {
            return MAPPER.readValue(filePath.toFile(),
                    MAPPER.getTypeFactory().constructMapType(ConcurrentHashMap.class, String.class, StateEntry.class));
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "レビュー状態ファイルの読み込みに失敗したため、空の状態で開始します: " + filePath, e);
            return new ConcurrentHashMap<>();
        }
    }

    private void persist() {
        try {
            Path parent = filePath.toAbsolutePath().getParent();
            Files.createDirectories(parent);
            Path tmp = Files.createTempFile(parent, "review-state", ".tmp");
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), state);
            Files.move(tmp, filePath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("レビュー状態の永続化に失敗しました: " + filePath, e);
        }
    }
}
