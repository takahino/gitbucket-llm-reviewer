package io.github.takahino.llmreviewer.rag;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagIndexStateStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void getVersionReturnsEmptyForUnknownKey() {
        RagIndexStateStore store = new RagIndexStateStore(tempDir.resolve("state.json"));
        assertTrue(store.getVersion("owner/repo").isEmpty());
    }

    @Test
    void markVersionIsRetrievableViaGet() {
        RagIndexStateStore store = new RagIndexStateStore(tempDir.resolve("state.json"));
        String key = RagIndexStateStore.key("owner", "repo");

        store.markVersion(key, "sha-1");

        Optional<String> version = store.getVersion(key);
        assertTrue(version.isPresent());
        assertEquals("sha-1", version.get());
    }

    @Test
    void statePersistsAcrossInstances() {
        Path file = tempDir.resolve("state.json");
        String key = RagIndexStateStore.key("owner", "repo");
        new RagIndexStateStore(file).markVersion(key, "sha-x");

        RagIndexStateStore reloaded = new RagIndexStateStore(file);
        assertEquals("sha-x", reloaded.getVersion(key).orElseThrow());
    }
}
