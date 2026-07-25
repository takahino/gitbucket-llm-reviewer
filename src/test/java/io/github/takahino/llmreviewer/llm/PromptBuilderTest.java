package io.github.takahino.llmreviewer.llm;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import io.github.takahino.llmreviewer.config.RepoReviewConfig;
import io.github.takahino.llmreviewer.git.DiffResult;
import io.github.takahino.llmreviewer.rag.RagSearchResult;
import io.github.takahino.llmreviewer.rag.RetrievedChunk;
import io.github.takahino.llmreviewer.scm.model.GitRef;
import io.github.takahino.llmreviewer.scm.model.PullRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptBuilderTest {

    private final PromptBuilder promptBuilder = new PromptBuilder(5);

    private static PullRequest samplePr() {
        return new PullRequest(
                1, "タイトル", "本文", "open", null,
                new GitRef("feature", "head-sha"), new GitRef("main", "base-sha"), null, null);
    }

    @Test
    void systemMessageUsesJapaneseWhenLanguageIsJaOrUnset() {
        String contentJa = ((dev.langchain4j.data.message.SystemMessage) promptBuilder.systemMessage("ja")).text();
        String contentNull = ((dev.langchain4j.data.message.SystemMessage) promptBuilder.systemMessage(null)).text();

        assertTrue(contentJa.contains("日本語"));
        assertTrue(contentNull.contains("日本語"));
    }

    @Test
    void systemMessageReflectsNonJapaneseLanguage() {
        String content = ((dev.langchain4j.data.message.SystemMessage) promptBuilder.systemMessage("en")).text();

        assertTrue(content.contains("「en」で記述してください"));
        assertFalse(content.contains("日本語"));
    }

    @Test
    void initialUserMessageOmitsRagSectionsWhenResultEmpty() {
        ChatMessage message = promptBuilder.initialUserMessage(
                samplePr(), RepoReviewConfig.defaultConfig(), List.of(), List.of(), Map.of(), Map.of(),
                RagSearchResult.empty(), new DiffResult("diff --git a/x b/x", false), null, Map.of(),
                PromptBuilder.BatchInfo.single());

        String text = ((UserMessage) message).singleText();
        assertFalse(text.contains("関連コード候補"));
        assertFalse(text.contains("関連コーディング規約抜粋"));
    }

    @Test
    void initialUserMessageIncludesRagSectionsWhenResultPresent() {
        RagSearchResult ragResult = new RagSearchResult(
                List.of(new RetrievedChunk("src/Foo.java", "class Foo {}", 0.9)),
                List.of(new RetrievedChunk("docs/coding-standards.md", "命名規則: camelCase", 0.8)));

        ChatMessage message = promptBuilder.initialUserMessage(
                samplePr(), RepoReviewConfig.defaultConfig(), List.of(), List.of(), Map.of(), Map.of(),
                ragResult, new DiffResult("diff --git a/x b/x", false), null, Map.of(),
                PromptBuilder.BatchInfo.single());

        String text = ((UserMessage) message).singleText();
        assertTrue(text.contains("関連コード候補"));
        assertTrue(text.contains("src/Foo.java"));
        assertTrue(text.contains("関連コーディング規約抜粋"));
        assertTrue(text.contains("docs/coding-standards.md"));
    }

    @Test
    void initialUserMessageOmitsBatchHeaderWhenBatchCountIsOne() {
        ChatMessage message = promptBuilder.initialUserMessage(
                samplePr(), RepoReviewConfig.defaultConfig(), List.of(), List.of(), Map.of(), Map.of(),
                RagSearchResult.empty(), new DiffResult("diff --git a/x b/x", false), null, Map.of(),
                new PromptBuilder.BatchInfo(1, 1, List.of("x")));

        String text = ((UserMessage) message).singleText();
        assertFalse(text.contains("## バッチ情報"));
    }

    @Test
    void initialUserMessageIncludesBatchHeaderWhenBatchCountGreaterThanOne() {
        ChatMessage message = promptBuilder.initialUserMessage(
                samplePr(), RepoReviewConfig.defaultConfig(), List.of(), List.of(), Map.of(), Map.of(),
                RagSearchResult.empty(), new DiffResult("diff --git a/x b/x", false), null, Map.of(),
                new PromptBuilder.BatchInfo(2, 3, List.of("src/A.java")));

        String text = ((UserMessage) message).singleText();
        assertTrue(text.contains("## バッチ情報"));
        assertTrue(text.contains("src/A.java"));
    }
}
