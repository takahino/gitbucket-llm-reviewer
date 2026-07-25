package io.github.takahino.llmreviewer.review;

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

/** PR毎の「メンション応答済みの最終コメントID」をJSONファイルに永続化し、二重応答を防止する。 */
public class MentionStateStore {

    private static final Logger LOGGER = Logger.getLogger(MentionStateStore.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path filePath;
    private final Map<String, Long> state;
    private final boolean dryRun;

    /** dryRun=trueの場合、markProcessedは何も記録しない(ReviewStateStoreと同方針)。 */
    public MentionStateStore(Path filePath, boolean dryRun) {
        this.filePath = filePath;
        this.state = new ConcurrentHashMap<>(load(filePath));
        this.dryRun = dryRun;
    }

    public static String key(String owner, String repo, int prNumber) {
        return "%s/%s#%d".formatted(owner, repo, prNumber);
    }

    /** そのPRのメンションコメントを一度も観測していなければ empty。 */
    public synchronized Optional<Long> get(String key) {
        return Optional.ofNullable(state.get(key));
    }

    /** 処理済み最終コメントIDを記録する。既存値以下のIDでは後退させない(単調増加を保証)。 */
    public synchronized void markProcessed(String key, long commentId) {
        if (dryRun) {
            return;
        }
        Long current = state.get(key);
        if (current != null && current >= commentId) {
            return;
        }
        state.put(key, commentId);
        persist();
    }

    private static Map<String, Long> load(Path filePath) {
        if (!Files.isRegularFile(filePath)) {
            return new ConcurrentHashMap<>();
        }
        try {
            return MAPPER.readValue(filePath.toFile(),
                    MAPPER.getTypeFactory().constructMapType(ConcurrentHashMap.class, String.class, Long.class));
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "メンション応答状態ファイルの読み込みに失敗したため、空の状態で開始します: " + filePath, e);
            return new ConcurrentHashMap<>();
        }
    }

    private void persist() {
        try {
            Path parent = filePath.toAbsolutePath().getParent();
            Files.createDirectories(parent);
            Path tmp = Files.createTempFile(parent, "mention-state", ".tmp");
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), state);
            Files.move(tmp, filePath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("メンション応答状態の永続化に失敗しました: " + filePath, e);
        }
    }
}
