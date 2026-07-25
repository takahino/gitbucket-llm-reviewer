package io.github.takahino.llmreviewer.review;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReviewStateStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void getReturnsEmptyForUnknownKey() {
        ReviewStateStore store = new ReviewStateStore(tempDir.resolve("state.json"));
        assertTrue(store.get("owner/repo#1").isEmpty());
    }

    @Test
    void markReviewedIsRetrievableViaGet() {
        ReviewStateStore store = new ReviewStateStore(tempDir.resolve("state.json"));
        String key = ReviewStateStore.key("owner", "repo", 1);

        store.markReviewed(key, "sha-1");

        Optional<ReviewStateStore.StateEntry> entry = store.get(key);
        assertTrue(entry.isPresent());
        assertEquals("sha-1", entry.get().reviewedHeadSha());
        assertEquals("reviewed", entry.get().status());
        assertFalse(store.needsReview(key, "sha-1"));
        assertTrue(store.needsReview(key, "sha-2"));
    }

    @Test
    void markFailedIncrementsFailureCountUntilSkipped() {
        ReviewStateStore store = new ReviewStateStore(tempDir.resolve("state.json"));
        String key = ReviewStateStore.key("owner", "repo", 2);

        store.markFailed(key, "sha-1", 3);
        store.markFailed(key, "sha-1", 3);
        assertTrue(store.needsReview(key, "sha-1"), "失敗上限未満なら再レビュー対象");

        store.markFailed(key, "sha-1", 3);
        Optional<ReviewStateStore.StateEntry> entry = store.get(key);
        assertEquals("skipped", entry.get().status());
        assertFalse(store.needsReview(key, "sha-1"), "失敗上限に達したらskip扱いで再レビューしない");
    }

    @Test
    void statePersistsAcrossInstances() {
        Path file = tempDir.resolve("state.json");
        String key = ReviewStateStore.key("owner", "repo", 3);
        new ReviewStateStore(file).markReviewed(key, "sha-x");

        ReviewStateStore reloaded = new ReviewStateStore(file);
        assertEquals("sha-x", reloaded.get(key).orElseThrow().reviewedHeadSha());
    }
}
