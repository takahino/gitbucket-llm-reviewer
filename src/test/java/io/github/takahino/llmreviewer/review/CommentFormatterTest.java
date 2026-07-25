package io.github.takahino.llmreviewer.review;

import io.github.takahino.llmreviewer.git.UnifiedDiffIndex;
import io.github.takahino.llmreviewer.llm.model.Finding;
import io.github.takahino.llmreviewer.llm.model.ReviewOutput;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommentFormatterTest {

    private static final String SAMPLE_DIFF = """
            diff --git a/src/Foo.java b/src/Foo.java
            index 111..222 100644
            --- a/src/Foo.java
            +++ b/src/Foo.java
            @@ -1,2 +1,2 @@
             line1
            -line2
            +line2modified
            """;

    @Test
    void formatIncludesMarkerSummaryAndFindings() {
        ReviewOutput output = new ReviewOutput("complete", List.of(), "サマリです",
                List.of(new Finding("src/Foo.java", 2, "warning", "命名規則", "命名を見直してください")));
        UnifiedDiffIndex diffIndex = UnifiedDiffIndex.parse(SAMPLE_DIFF);

        String body = CommentFormatter.format(output, "abc1234567", "test-model", List.of(), 10, diffIndex, null);

        assertTrue(body.contains(CommentFormatter.marker("abc1234567")));
        assertTrue(body.contains("サマリです"));
        assertTrue(body.contains("src/Foo.java:2"));
        assertTrue(body.contains("命名を見直してください"));
        assertTrue(body.contains("+line2modified"), "該当行のスニペットが引用されること");
    }

    @Test
    void formatFallsBackToFileLineWhenSnippetNotFound() {
        ReviewOutput output = new ReviewOutput("complete", List.of(), "サマリ",
                List.of(new Finding("src/Foo.java", 999, "info", "観点", "コメント")));
        UnifiedDiffIndex diffIndex = UnifiedDiffIndex.parse(SAMPLE_DIFF);

        String body = CommentFormatter.format(output, "abc1234567", "test-model", List.of(), 10, diffIndex, null);

        assertTrue(body.contains("src/Foo.java:999"));
        assertFalse(body.contains("```diff"), "該当箇所が見つからない場合は引用ブロックを付けない");
    }

    @Test
    void formatAddsIncrementalScopeNoteWhenPreviousHeadShaGiven() {
        ReviewOutput output = new ReviewOutput("complete", List.of(), "サマリ", List.of());
        UnifiedDiffIndex diffIndex = UnifiedDiffIndex.parse(SAMPLE_DIFF);

        String body = CommentFormatter.format(output, "abc1234567", "test-model", List.of(), 10, diffIndex, "deadbeef00");

        assertTrue(body.contains("前回レビュー"));
        assertTrue(body.contains("deadbeef00".substring(0, 10)));
    }

    @Test
    void isBotCommentDetectsMarkerOnly() {
        assertTrue(CommentFormatter.isBotComment(CommentFormatter.marker("sha") + "\n本文"));
        assertFalse(CommentFormatter.isBotComment("人間が書いた普通のコメント"));
        assertFalse(CommentFormatter.isBotComment(null));
    }
}
