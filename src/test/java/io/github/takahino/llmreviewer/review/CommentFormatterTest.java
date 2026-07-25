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
    void formatSummaryIncludesMarkerAndSummaryText() {
        ReviewOutput output = new ReviewOutput("complete", List.of(), "サマリです", List.of());

        String body = CommentFormatter.formatSummary(output, "abc1234567", "test-model", List.of(), null);

        assertTrue(body.contains(CommentFormatter.summaryMarker("abc1234567")));
        assertTrue(body.contains("## 変更サマリ"));
        assertTrue(body.contains("サマリです"));
        assertFalse(body.contains("## 指摘事項"), "サマリコメントに指摘事項セクションは含まれないこと");
    }

    @Test
    void formatSummaryIncludesReferencedAdditionalFiles() {
        ReviewOutput output = new ReviewOutput("complete", List.of(), "サマリです", List.of());

        String body = CommentFormatter.formatSummary(
                output, "abc1234567", "test-model", List.of("src/Bar.java"), null);

        assertTrue(body.contains("参照した追加ファイル"));
        assertTrue(body.contains("src/Bar.java"));
    }

    @Test
    void formatFindingsIncludesMarkerLocationAndSnippet() {
        ReviewOutput output = new ReviewOutput("complete", List.of(), "サマリです",
                List.of(new Finding("src/Foo.java", 2, "warning", "命名規則", "命名を見直してください")));
        UnifiedDiffIndex diffIndex = UnifiedDiffIndex.parse(SAMPLE_DIFF);

        String body = CommentFormatter.formatFindings(
                output, "abc1234567", "test-model", List.of(), 10, diffIndex, null);

        assertTrue(body.contains(CommentFormatter.findingsMarker("abc1234567")));
        assertTrue(body.contains("## 指摘事項"));
        assertFalse(body.contains("## 変更サマリ"), "指摘事項コメントにサマリセクションは含まれないこと");
        assertTrue(body.contains("src/Foo.java:2"));
        assertTrue(body.contains("命名を見直してください"));
        assertTrue(body.contains("+line2modified"), "該当行のスニペットが引用されること");
    }

    @Test
    void formatFindingsIncludesReferencedAdditionalFiles() {
        ReviewOutput output = new ReviewOutput("complete", List.of(), "サマリ", List.of());
        UnifiedDiffIndex diffIndex = UnifiedDiffIndex.parse(SAMPLE_DIFF);

        String body = CommentFormatter.formatFindings(
                output, "abc1234567", "test-model", List.of("src/Bar.java"), 10, diffIndex, null);

        assertTrue(body.contains("参照した追加ファイル"));
        assertTrue(body.contains("src/Bar.java"));
    }

    @Test
    void formatFindingsFallsBackToFileLineWhenSnippetNotFound() {
        ReviewOutput output = new ReviewOutput("complete", List.of(), "サマリ",
                List.of(new Finding("src/Foo.java", 999, "info", "観点", "コメント")));
        UnifiedDiffIndex diffIndex = UnifiedDiffIndex.parse(SAMPLE_DIFF);

        String body = CommentFormatter.formatFindings(
                output, "abc1234567", "test-model", List.of(), 10, diffIndex, null);

        assertTrue(body.contains("src/Foo.java:999"));
        assertFalse(body.contains("```diff"), "該当箇所が見つからない場合は引用ブロックを付けない");
    }

    @Test
    void formatAddsIncrementalScopeNoteWhenPreviousHeadShaGiven() {
        ReviewOutput output = new ReviewOutput("complete", List.of(), "サマリ", List.of());
        UnifiedDiffIndex diffIndex = UnifiedDiffIndex.parse(SAMPLE_DIFF);

        String summaryBody = CommentFormatter.formatSummary(
                output, "abc1234567", "test-model", List.of(), "deadbeef00");
        String findingsBody = CommentFormatter.formatFindings(
                output, "abc1234567", "test-model", List.of(), 10, diffIndex, "deadbeef00");

        assertTrue(summaryBody.contains("前回レビュー"));
        assertTrue(summaryBody.contains("deadbeef00".substring(0, 10)));
        assertTrue(findingsBody.contains("前回レビュー"));
        assertTrue(findingsBody.contains("deadbeef00".substring(0, 10)));
    }
}
