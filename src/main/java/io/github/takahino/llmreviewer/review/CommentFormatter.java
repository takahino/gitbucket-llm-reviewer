package io.github.takahino.llmreviewer.review;

import io.github.takahino.llmreviewer.git.UnifiedDiffIndex;
import io.github.takahino.llmreviewer.llm.model.Finding;
import io.github.takahino.llmreviewer.llm.model.ReviewOutput;

import java.util.List;

/** LLMのレビュー結果をGitBucketのPRコメント用Markdownに整形する。 */
public final class CommentFormatter {

    /** bot自身が投稿したコメントを識別するためのマーカープレフィックス(折りたたみ対象の判定に使用)。 */
    private static final String MARKER_PREFIX = "<!-- gitbucket-llm-reviewer";

    private static final int SNIPPET_CONTEXT_LINES = 3;

    private CommentFormatter() {
    }

    /** 変更サマリコメント用のマーカー(サマリ・指摘事項を別コメントとして区別するために種別を含む)。 */
    public static String summaryMarker(String headSha) {
        return marker("summary", headSha);
    }

    /** 指摘事項コメント用のマーカー。 */
    public static String findingsMarker(String headSha) {
        return marker("findings", headSha);
    }

    private static String marker(String kind, String headSha) {
        return "%s:%s: %s -->".formatted(MARKER_PREFIX, kind, headSha);
    }

    /** 変更サマリのみを含むコメント本文を組み立てる。 */
    public static String formatSummary(
            ReviewOutput output,
            String headSha,
            String modelName,
            List<String> referencedAdditionalFiles,
            String incrementalPreviousHeadSha
    ) {
        StringBuilder sb = new StringBuilder();
        // マーカー行(HTMLコメント)の直後に空行を挟まないと、Markdownパーサーが後続の見出しやコード
        // ブロックまでHTMLブロックの一部とみなし、生テキストのまま表示してしまうことがあるため。
        sb.append(summaryMarker(headSha)).append("\n\n");
        sb.append("## 変更サマリ\n");
        appendIncrementalScopeNote(sb, incrementalPreviousHeadSha);
        sb.append(isBlank(output.summary()) ? "(サマリなし)" : output.summary()).append("\n\n");
        appendFooter(sb, headSha, modelName, referencedAdditionalFiles);
        return sb.toString();
    }

    /** 指摘事項のみを含むコメント本文を組み立てる。 */
    public static String formatFindings(
            ReviewOutput output,
            String headSha,
            String modelName,
            List<String> referencedAdditionalFiles,
            int maxComments,
            UnifiedDiffIndex diffIndex,
            String incrementalPreviousHeadSha
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append(findingsMarker(headSha)).append("\n\n");
        sb.append("## 指摘事項\n");
        appendIncrementalScopeNote(sb, incrementalPreviousHeadSha);

        List<Finding> findings = output.findings();
        if (findings.isEmpty()) {
            sb.append("特に指摘事項はありませんでした。\n");
        } else {
            boolean truncated = findings.size() > maxComments;
            List<Finding> displayed = truncated ? findings.subList(0, maxComments) : findings;
            for (Finding f : displayed) {
                String location = f.line() != null ? "%s:%d".formatted(f.file(), f.line()) : String.valueOf(f.file());
                sb.append("- **[").append(severityLabel(f.severity())).append("] ").append(location).append("**");
                if (!isBlank(f.perspective())) {
                    sb.append(" (観点: ").append(f.perspective()).append(")");
                }
                sb.append("\n  ").append(f.comment()).append("\n");
                appendSnippetIfAvailable(sb, diffIndex, f);
            }
            if (truncated) {
                sb.append("\n(他 ").append(findings.size() - maxComments).append(" 件の指摘は表示上限のため省略されました)\n");
            }
        }
        sb.append('\n');
        appendFooter(sb, headSha, modelName, referencedAdditionalFiles);
        return sb.toString();
    }

    private static void appendIncrementalScopeNote(StringBuilder sb, String incrementalPreviousHeadSha) {
        if (incrementalPreviousHeadSha != null) {
            sb.append("> 本レビューは前回レビュー(head: `").append(shortSha(incrementalPreviousHeadSha))
                    .append("`)以降の差分のみが対象です。\n\n");
        }
    }

    /** サマリ・指摘事項の両コメント共通のフッター(モデル名/head/参照した追加ファイル)を付記する。 */
    private static void appendFooter(StringBuilder sb, String headSha, String modelName, List<String> referencedAdditionalFiles) {
        sb.append("---\n");
        sb.append("モデル: `").append(modelName).append("` / head: `").append(shortSha(headSha)).append("`");
        if (!referencedAdditionalFiles.isEmpty()) {
            sb.append(" / 参照した追加ファイル: ").append(String.join(", ", referencedAdditionalFiles));
        }
        sb.append('\n');
    }

    /**
     * GitBucketにはdiff行への直接コメントAPIが無いため、該当行の周辺コードを引用することで
     * 疑似的にインラインコメントに近い体験を提供する。該当箇所が見つからない場合は何も追加しない。
     */
    private static void appendSnippetIfAvailable(StringBuilder sb, UnifiedDiffIndex diffIndex, Finding f) {
        if (f.line() == null) {
            return;
        }
        diffIndex.snippet(f.file(), f.line(), SNIPPET_CONTEXT_LINES).ifPresent(snippet ->
                sb.append("  ```diff\n").append(snippet.indent(2)).append("  ```\n"));
    }

    private static String severityLabel(String severity) {
        if ("error".equals(severity) || "warning".equals(severity)) {
            return severity;
        }
        return "info";
    }

    private static String shortSha(String sha) {
        return sha.length() > 10 ? sha.substring(0, 10) : sha;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
