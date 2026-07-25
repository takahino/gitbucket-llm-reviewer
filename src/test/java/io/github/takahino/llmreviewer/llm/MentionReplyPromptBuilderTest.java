package io.github.takahino.llmreviewer.llm;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import io.github.takahino.llmreviewer.config.RepoReviewConfig;
import io.github.takahino.llmreviewer.git.DiffResult;
import io.github.takahino.llmreviewer.rag.RagSearchResult;
import io.github.takahino.llmreviewer.scm.model.Account;
import io.github.takahino.llmreviewer.scm.model.GitRef;
import io.github.takahino.llmreviewer.scm.model.IssueComment;
import io.github.takahino.llmreviewer.scm.model.PullRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MentionReplyPromptBuilderTest {

    private final MentionReplyPromptBuilder promptBuilder = new MentionReplyPromptBuilder(5);

    private static PullRequest samplePr() {
        return new PullRequest(
                1, "タイトル", "本文", "open", null,
                new GitRef("feature", "head-sha"), new GitRef("main", "base-sha"), null, null);
    }

    private static IssueComment triggerComment(String login, String body) {
        return new IssueComment(100, body, new Account(login));
    }

    @Test
    void omitsPerspectiveSectionWhenGroupsEmpty() {
        ChatMessage message = promptBuilder.initialUserMessage(
                samplePr(), List.of(), List.of(), Map.of(), Map.of(), RagSearchResult.empty(),
                new DiffResult("diff --git a/x b/x", false), List.of(),
                triggerComment("alice", "ここは大丈夫?"), "review-bot");

        String text = ((UserMessage) message).singleText();
        assertFalse(text.contains("## レビュー観点"));
        assertTrue(text.contains("ここは大丈夫?"), "review.yml不在時は質問文をそのまま含めること");
    }

    @Test
    void includesPerspectiveSectionWhenGroupsPresent() {
        RepoReviewConfig.PerspectiveEntry entry = new RepoReviewConfig.PerspectiveEntry("セキュリティ観点で見て", null);
        RepoReviewConfig.PerspectiveGroup group =
                new RepoReviewConfig.PerspectiveGroup("共通", List.of(entry), List.of("src/Foo.java"));

        ChatMessage message = promptBuilder.initialUserMessage(
                samplePr(), List.of(group), List.of(), Map.of(), Map.of(), RagSearchResult.empty(),
                new DiffResult("diff --git a/x b/x", false), List.of(),
                triggerComment("alice", "再レビューして"), "review-bot");

        String text = ((UserMessage) message).singleText();
        assertTrue(text.contains("## レビュー観点"));
        assertTrue(text.contains("セキュリティ観点で見て"));
    }

    @Test
    void includesCommentHistoryAndLabelsBotSelf() {
        List<IssueComment> history = List.of(
                new IssueComment(1, "サマリコメント", new Account("review-bot")),
                new IssueComment(2, "ここが気になる", new Account("alice")));

        ChatMessage message = promptBuilder.initialUserMessage(
                samplePr(), List.of(), List.of(), Map.of(), Map.of(), RagSearchResult.empty(),
                new DiffResult("diff --git a/x b/x", false), history,
                triggerComment("alice", "追加で教えて"), "review-bot");

        String text = ((UserMessage) message).singleText();
        assertTrue(text.contains("## これまでのPRコメントのやり取り"));
        assertTrue(text.contains("Bot自身の過去の発言"));
        assertTrue(text.contains("@alice"));
    }

    @Test
    void alwaysIncludesTriggerCommentBody() {
        ChatMessage message = promptBuilder.initialUserMessage(
                samplePr(), List.of(), List.of(), Map.of(), Map.of(), RagSearchResult.empty(),
                new DiffResult("diff --git a/x b/x", false), List.of(),
                triggerComment("alice", "この関数の意図を教えて"), "review-bot");

        String text = ((UserMessage) message).singleText();
        assertTrue(text.contains("## ユーザーからの質問/追加レビュー依頼"));
        assertTrue(text.contains("この関数の意図を教えて"));
    }
}
