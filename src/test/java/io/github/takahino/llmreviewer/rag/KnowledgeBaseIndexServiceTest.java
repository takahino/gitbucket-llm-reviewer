package io.github.takahino.llmreviewer.rag;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import io.github.takahino.llmreviewer.config.AppConfig;
import io.github.takahino.llmreviewer.git.RepositoryReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeBaseIndexServiceTest {

    @TempDir
    Path tempDir;

    /** 文字列内容から決定的なベクトルを生成するテスト用embedding。実サーバー不要。 */
    private static class DeterministicEmbeddingModel implements EmbeddingModel {
        final AtomicInteger embedCallCount = new AtomicInteger();

        @Override
        public Response<Embedding> embed(String text) {
            embedCallCount.incrementAndGet();
            return new Response<>(vectorFor(text));
        }

        @Override
        public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
            embedCallCount.addAndGet(segments.size());
            return new Response<>(segments.stream().map(s -> vectorFor(s.text())).toList());
        }

        private static Embedding vectorFor(String text) {
            int h = text.hashCode();
            float[] vector = new float[4];
            for (int i = 0; i < vector.length; i++) {
                vector[i] = ((h >> (i * 8)) & 0xFF) / 255f;
            }
            return Embedding.from(vector);
        }
    }

    private static class StubRepositoryReader implements RepositoryReader {
        final Map<String, String> filesByPath;

        StubRepositoryReader(Map<String, String> filesByPath) {
            this.filesByPath = filesByPath;
        }

        @Override
        public List<String> listFiles(String owner, String repo, String ref, int maxFiles) {
            return List.copyOf(filesByPath.keySet());
        }

        @Override
        public Optional<String> readFile(String owner, String repo, String ref, String path) {
            return Optional.ofNullable(filesByPath.get(path));
        }
    }

    private AppConfig.RagConfig ragConfig() {
        return new AppConfig.RagConfig(
                true, "ollama", "http://localhost:11434", "test-model", "",
                5, 0.0, 500, 50, 100, List.of(".md"), tempDir.resolve("index").toString());
    }

    @Test
    void firstIndexingEmbedsAllKnowledgeBaseFiles() {
        Map<String, String> files = new HashMap<>();
        files.put("docs/coding-standards.md", "命名規則: camelCaseを使うこと");
        StubRepositoryReader reader = new StubRepositoryReader(files);
        DeterministicEmbeddingModel embeddingModel = new DeterministicEmbeddingModel();
        RagIndexStateStore stateStore = new RagIndexStateStore(tempDir.resolve("state.json"));
        KnowledgeBaseIndexService service =
                new KnowledgeBaseIndexService(reader, embeddingModel, stateStore, ragConfig());

        InMemoryEmbeddingStore<TextSegment> store =
                service.ensureIndexed("owner", "repo", "sha-1", List.of("docs/coding-standards.md"));

        assertTrue(embeddingModel.embedCallCount.get() > 0, "初回は少なくとも1回embedされること");
        assertFalse(store.isEmpty(), "ファイル内容がストアにチャンクとして格納されること");
    }

    @Test
    void secondCallWithUnchangedContentDoesNotReembed() {
        Map<String, String> files = new HashMap<>();
        files.put("docs/coding-standards.md", "命名規則: camelCaseを使うこと");
        StubRepositoryReader reader = new StubRepositoryReader(files);
        DeterministicEmbeddingModel embeddingModel = new DeterministicEmbeddingModel();
        RagIndexStateStore stateStore = new RagIndexStateStore(tempDir.resolve("state.json"));
        KnowledgeBaseIndexService service =
                new KnowledgeBaseIndexService(reader, embeddingModel, stateStore, ragConfig());

        service.ensureIndexed("owner", "repo", "sha-1", List.of("docs/coding-standards.md"));
        int countAfterFirst = embeddingModel.embedCallCount.get();

        service.ensureIndexed("owner", "repo", "sha-2", List.of("docs/coding-standards.md"));
        int countAfterSecond = embeddingModel.embedCallCount.get();

        assertEquals(countAfterFirst, countAfterSecond, "内容が変わっていなければ再embedされないこと");
    }

    @Test
    void reindexesWhenContentChanges() {
        Map<String, String> files = new HashMap<>();
        files.put("docs/coding-standards.md", "命名規則: camelCaseを使うこと");
        StubRepositoryReader reader = new StubRepositoryReader(files);
        DeterministicEmbeddingModel embeddingModel = new DeterministicEmbeddingModel();
        RagIndexStateStore stateStore = new RagIndexStateStore(tempDir.resolve("state.json"));
        KnowledgeBaseIndexService service =
                new KnowledgeBaseIndexService(reader, embeddingModel, stateStore, ragConfig());

        service.ensureIndexed("owner", "repo", "sha-1", List.of("docs/coding-standards.md"));
        int countAfterFirst = embeddingModel.embedCallCount.get();

        files.put("docs/coding-standards.md", "命名規則: snake_caseを使うこと(変更後)");
        service.ensureIndexed("owner", "repo", "sha-2", List.of("docs/coding-standards.md"));
        int countAfterChange = embeddingModel.embedCallCount.get();

        assertTrue(countAfterChange > countAfterFirst, "内容が変わったら再embedされること");
    }
}
