package io.github.takahino.llmreviewer.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppConfigWriterTest {

    private AppConfig sampleConfig(String baseUrl) {
        return new AppConfig(
                "gitbucket",
                new AppConfig.GitBucketConfig(baseUrl, "token-value", "", "", ""),
                List.of(new AppConfig.RepositoryRef("owner", "repo")),
                new AppConfig.PollingConfig(30),
                new AppConfig.LlmConfig("http://localhost:11434/v1", "qwen2.5-coder:14b", "", 0.2, 4096, 300, 3, 2000),
                new AppConfig.ReviewConfig(60000, 5, 50000, 3),
                new AppConfig.RagConfig(
                        false, "ollama", "http://localhost:11434", "nomic-embed-text", "", 5, 0.65, 500, 50, 3000,
                        List.of(".java", ".md"), "./data/rag-index"),
                new AppConfig.StateConfig("./data/review-state.json", "./data/mention-state.json"),
                "./data/repos"
        );
    }

    @Test
    void writeThenLoadRoundTripsValues(@TempDir Path tempDir) {
        Path configPath = tempDir.resolve("config.yml");
        AppConfig original = sampleConfig("http://localhost:8080");

        AppConfigWriter.write(configPath, original);
        AppConfig reloaded = AppConfigLoader.load(configPath);

        assertEquals(original, reloaded);
    }

    @Test
    void writeDoesNotLeaveTemporaryFile(@TempDir Path tempDir) {
        Path configPath = tempDir.resolve("config.yml");
        AppConfigWriter.write(configPath, sampleConfig("http://localhost:8080"));

        assertFalse(Files.exists(tempDir.resolve("config.yml.tmp")));
    }

    @Test
    void firstWriteBacksUpExistingFileOnce(@TempDir Path tempDir) throws IOException {
        Path configPath = tempDir.resolve("config.yml");
        String handWrittenContent = "# original hand-edited content\ngitbucket:\n  baseUrl: http://original\n";
        Files.writeString(configPath, handWrittenContent, StandardCharsets.UTF_8);
        Path backupPath = tempDir.resolve("config.yml.bak");

        AppConfigWriter.write(configPath, sampleConfig("http://first-write"));
        assertTrue(Files.exists(backupPath));
        assertEquals(handWrittenContent, Files.readString(backupPath, StandardCharsets.UTF_8));

        AppConfigWriter.write(configPath, sampleConfig("http://second-write"));
        assertEquals(handWrittenContent, Files.readString(backupPath, StandardCharsets.UTF_8),
                "2回目以降の書き込みでバックアップが上書きされてはならない");
    }
}
