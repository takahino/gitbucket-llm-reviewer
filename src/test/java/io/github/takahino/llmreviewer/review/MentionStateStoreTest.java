package io.github.takahino.llmreviewer.review;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MentionStateStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void getReturnsEmptyForUnknownKey() {
        MentionStateStore store = new MentionStateStore(tempDir.resolve("mention-state.json"), false);
        assertTrue(store.get("owner/repo#1").isEmpty());
    }

    @Test
    void markProcessedIsRetrievableViaGet() {
        MentionStateStore store = new MentionStateStore(tempDir.resolve("mention-state.json"), false);
        String key = MentionStateStore.key("owner", "repo", 1);

        store.markProcessed(key, 10L);

        Optional<Long> value = store.get(key);
        assertTrue(value.isPresent());
        assertEquals(10L, value.get());
    }

    @Test
    void markProcessedDoesNotRegressToOlderId() {
        MentionStateStore store = new MentionStateStore(tempDir.resolve("mention-state.json"), false);
        String key = MentionStateStore.key("owner", "repo", 1);

        store.markProcessed(key, 10L);
        store.markProcessed(key, 5L);

        assertEquals(10L, store.get(key).orElseThrow());
    }

    @Test
    void dryRunSkipsMarkProcessed() {
        MentionStateStore store = new MentionStateStore(tempDir.resolve("mention-state.json"), true);
        String key = MentionStateStore.key("owner", "repo", 1);

        store.markProcessed(key, 10L);

        assertTrue(store.get(key).isEmpty(), "dry-run時はmarkProcessedで状態を記録しない");
    }

    @Test
    void statePersistsAcrossInstances() {
        Path file = tempDir.resolve("mention-state.json");
        String key = MentionStateStore.key("owner", "repo", 3);
        new MentionStateStore(file, false).markProcessed(key, 42L);

        MentionStateStore reloaded = new MentionStateStore(file, false);
        assertEquals(42L, reloaded.get(key).orElseThrow());
    }
}
