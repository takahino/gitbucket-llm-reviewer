package io.github.takahino.llmreviewer.review;

import io.github.takahino.llmreviewer.git.UnifiedDiffIndex;
import io.github.takahino.llmreviewer.llm.model.Finding;
import io.github.takahino.llmreviewer.llm.model.MentionReplyOutput;
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
        assertFalse(body.contains("## 参照したコンテキストファイル"), "参照ファイルが空の場合はセクション自体を出さないこと");
    }

    @Test
    void formatSummaryIncludesReferencedAdditionalFiles() {
        ReviewOutput output = new ReviewOutput("complete", List.of(), "サマリです", List.of());

        String body = CommentFormatter.formatSummary(
                output, "abc1234567", "test-model",
                List.of(new CommentFormatter.ReferencedFile("src/Bar.java", CommentFormatter.ReferencedFile.Kind.DYNAMICALLY_FETCHED)),
                null);

        assertTrue(body.contains("## 参照したコンテキストファイル"));
        assertTrue(body.contains("- src/Bar.java (追加取得)"));
    }

    @Test
    void formatSummaryListsReferencedContextFilesWithKindLabels() {
        ReviewOutput output = new ReviewOutput("complete", List.of(), "サマリです", List.of());
        List<CommentFormatter.ReferencedFile> referenced = List.of(
                new CommentFormatter.ReferencedFile("docs/CONVENTIONS.md", CommentFormatter.ReferencedFile.Kind.ALWAYS_CONTEXT),
                new CommentFormatter.ReferencedFile("docs/PERSPECTIVE.md", CommentFormatter.ReferencedFile.Kind.PERSPECTIVE_CONTEXT),
                new CommentFormatter.ReferencedFile("src/Related.java", CommentFormatter.ReferencedFile.Kind.RAG_RELATED_CODE),
                new CommentFormatter.ReferencedFile("docs/RULES.md", CommentFormatter.ReferencedFile.Kind.RAG_KNOWLEDGE_BASE),
                new CommentFormatter.ReferencedFile("src/Foo.java", CommentFormatter.ReferencedFile.Kind.FULL_FILE_CONTEXT),
                new CommentFormatter.ReferencedFile("src/Bar.java", CommentFormatter.ReferencedFile.Kind.DYNAMICALLY_FETCHED)
        );

        String body = CommentFormatter.formatSummary(output, "abc1234567", "test-model", referenced, null);

        assertTrue(body.contains("## 参照したコンテキストファイル"));
        assertTrue(body.contains("- docs/CONVENTIONS.md (常時コンテキスト)"));
        assertTrue(body.contains("- docs/PERSPECTIVE.md (観点別コンテキスト)"));
        assertTrue(body.contains("- src/Related.java (関連コード候補(RAG))"));
        assertTrue(body.contains("- docs/RULES.md (関連コーディング規約(RAG))"));
        assertTrue(body.contains("- src/Foo.java (全文コンテキスト)"));
        assertTrue(body.contains("- src/Bar.java (追加取得)"));
    }

    @Test
    void formatFindingsIncludesMarkerLocationAndSnippet() {
        ReviewOutput output = new ReviewOutput("complete", List.of(), "サマリです",
                List.of(new Finding("src/Foo.java", 2, "warning", "命名規則", "命名を見直してください")));
        UnifiedDiffIndex diffIndex = UnifiedDiffIndex.parse(SAMPLE_DIFF);

        String body = CommentFormatter.formatFindings(
                output, "abc1234567", "test-model", List.of(), 10, diffIndex, null, null);

        assertTrue(body.contains(CommentFormatter.findingsMarker("abc1234567")));
        assertTrue(body.contains("## 指摘事項"));
        assertFalse(body.contains("## 変更サマリ"), "指摘事項コメントにサマリセクションは含まれないこと");
        assertTrue(body.contains("src/Foo.java:2"));
        assertTrue(body.contains("命名を見直してください"));
        assertTrue(body.contains("+line2modified"), "該当行のスニペットが引用されること");
    }

    @Test
    void formatFindingsLinksFileWhenBlobBaseUrlGiven() {
        ReviewOutput output = new ReviewOutput("complete", List.of(), "サマリです",
                List.of(new Finding("src/Foo.java", 2, "warning", "命名規則", "命名を見直してください")));
        UnifiedDiffIndex diffIndex = UnifiedDiffIndex.parse(SAMPLE_DIFF);

        String body = CommentFormatter.formatFindings(
                output, "abc1234567", "test-model", List.of(), 10, diffIndex, null,
                "http://localhost:8080/root/sample/blob/abc1234567");

        assertTrue(body.contains("[src/Foo.java:2](http://localhost:8080/root/sample/blob/abc1234567/src/Foo.java#L2)"));
    }

    @Test
    void formatFindingsSortsBySeverityPriorityBeforeTruncating() {
        ReviewOutput output = new ReviewOutput("complete", List.of(), "サマリ", List.of(
                new Finding("src/A.java", null, "info", "観点", "info指摘"),
                new Finding("src/B.java", null, "error", "観点", "error指摘"),
                new Finding("src/C.java", null, "warning", "観点", "warning指摘")
        ));
        UnifiedDiffIndex diffIndex = UnifiedDiffIndex.parse(SAMPLE_DIFF);

        String body = CommentFormatter.formatFindings(
                output, "abc1234567", "test-model", List.of(), 10, diffIndex, null, null);

        int errorIdx = body.indexOf("error指摘");
        int warningIdx = body.indexOf("warning指摘");
        int infoIdx = body.indexOf("info指摘");
        assertTrue(errorIdx >= 0 && errorIdx < warningIdx && warningIdx < infoIdx, "error > warning > info の順で並ぶこと");
    }

    @Test
    void formatFindingsIncludesReferencedAdditionalFiles() {
        ReviewOutput output = new ReviewOutput("complete", List.of(), "サマリ", List.of());
        UnifiedDiffIndex diffIndex = UnifiedDiffIndex.parse(SAMPLE_DIFF);

        String body = CommentFormatter.formatFindings(
                output, "abc1234567", "test-model",
                List.of(new CommentFormatter.ReferencedFile("src/Bar.java", CommentFormatter.ReferencedFile.Kind.DYNAMICALLY_FETCHED)),
                10, diffIndex, null, null);

        assertTrue(body.contains("## 参照したコンテキストファイル"));
        assertTrue(body.contains("- src/Bar.java (追加取得)"));
    }

    @Test
    void formatFindingsFallsBackToFileLineWhenSnippetNotFound() {
        ReviewOutput output = new ReviewOutput("complete", List.of(), "サマリ",
                List.of(new Finding("src/Foo.java", 999, "info", "観点", "コメント")));
        UnifiedDiffIndex diffIndex = UnifiedDiffIndex.parse(SAMPLE_DIFF);

        String body = CommentFormatter.formatFindings(
                output, "abc1234567", "test-model", List.of(), 10, diffIndex, null, null);

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
                output, "abc1234567", "test-model", List.of(), 10, diffIndex, "deadbeef00", null);

        assertTrue(summaryBody.contains("前回レビュー"));
        assertTrue(summaryBody.contains("deadbeef00".substring(0, 10)));
        assertTrue(findingsBody.contains("前回レビュー"));
        assertTrue(findingsBody.contains("deadbeef00".substring(0, 10)));
    }

    @Test
    void formatMentionReplyIncludesMarkerAnswerAndTriggerUser() {
        MentionReplyOutput output = new MentionReplyOutput("complete", List.of(), "この関数はnullを返しません");

        String body = CommentFormatter.formatMentionReply(
                output, "abc1234567", "test-model", List.of(), 42L, "alice");

        assertTrue(body.contains(CommentFormatter.mentionReplyMarker(42L)));
        assertTrue(body.contains("## 回答"));
        assertTrue(body.contains("@alice"));
        assertTrue(body.contains("この関数はnullを返しません"));
    }

    @Test
    void formatMentionReplyListsReferencedContextFilesWithKindLabels() {
        MentionReplyOutput output = new MentionReplyOutput("complete", List.of(), "回答です");
        List<CommentFormatter.ReferencedFile> referenced = List.of(
                new CommentFormatter.ReferencedFile("docs/CONVENTIONS.md", CommentFormatter.ReferencedFile.Kind.ALWAYS_CONTEXT),
                new CommentFormatter.ReferencedFile("src/Bar.java", CommentFormatter.ReferencedFile.Kind.DYNAMICALLY_FETCHED)
        );

        String body = CommentFormatter.formatMentionReply(output, "abc1234567", "test-model", referenced, 42L, "alice");

        assertTrue(body.contains("## 参照したコンテキストファイル"));
        assertTrue(body.contains("- docs/CONVENTIONS.md (常時コンテキスト)"));
        assertTrue(body.contains("- src/Bar.java (追加取得)"));
    }

    @Test
    void mentionReplyMarkerDiffersByTriggerCommentId() {
        assertFalse(CommentFormatter.mentionReplyMarker(1L).equals(CommentFormatter.mentionReplyMarker(2L)));
    }
}
