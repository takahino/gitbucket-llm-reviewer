package io.github.takahino.llmreviewer.review;

import io.github.takahino.llmreviewer.git.UnifiedDiffIndex;
import io.github.takahino.llmreviewer.llm.model.Finding;
import io.github.takahino.llmreviewer.llm.model.MentionReplyOutput;
import io.github.takahino.llmreviewer.llm.model.ReviewOutput;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

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

    /** メンション応答コメント用のマーカー。同一PRで複数回メンションされても区別できるよう、トリガーとなったコメントIDを含める。 */
    public static String mentionReplyMarker(long triggerCommentId) {
        return "%s:mentionReply:%d -->".formatted(MARKER_PREFIX, triggerCommentId);
    }

    /**
     * フッターの「参照したコンテキストファイル」一覧の1行分。LLMに実際に見せたファイルの
     * パスと、どの経路(常時/観点別/RAG/全文/動的取得)で渡されたかを保持する。
     * 同一(path, kind)の重複除去はOrchestrator側の収集ロジックが担う(このrecordはレンダリングのみ)。
     */
    public record ReferencedFile(String path, Kind kind) {

        /** LLMに渡した経路の種別。表示ラベルは日本語で固定する。 */
        public enum Kind {
            ALWAYS_CONTEXT("常時コンテキスト"),
            PERSPECTIVE_CONTEXT("観点別コンテキスト"),
            RAG_RELATED_CODE("関連コード候補(RAG)"),
            RAG_KNOWLEDGE_BASE("関連コーディング規約(RAG)"),
            FULL_FILE_CONTEXT("全文コンテキスト"),
            DYNAMICALLY_FETCHED("追加取得");

            private final String label;

            Kind(String label) {
                this.label = label;
            }

            public String label() {
                return label;
            }
        }
    }

    /** 変更サマリのみを含むコメント本文を組み立てる。 */
    public static String formatSummary(
            ReviewOutput output,
            String headSha,
            String modelName,
            List<ReferencedFile> referencedFiles,
            String incrementalPreviousHeadSha
    ) {
        StringBuilder sb = new StringBuilder();
        // マーカー行(HTMLコメント)の直後に空行を挟まないと、Markdownパーサーが後続の見出しやコード
        // ブロックまでHTMLブロックの一部とみなし、生テキストのまま表示してしまうことがあるため。
        sb.append(summaryMarker(headSha)).append("\n\n");
        sb.append("## 変更サマリ\n");
        appendIncrementalScopeNote(sb, incrementalPreviousHeadSha);
        sb.append(isBlank(output.summary()) ? "(サマリなし)" : output.summary()).append("\n\n");
        appendFooter(sb, headSha, modelName, referencedFiles);
        return sb.toString();
    }

    /** 指摘事項のみを含むコメント本文を組み立てる。 */
    public static String formatFindings(
            ReviewOutput output,
            String headSha,
            String modelName,
            List<ReferencedFile> referencedFiles,
            int maxComments,
            UnifiedDiffIndex diffIndex,
            String incrementalPreviousHeadSha,
            String fileBlobBaseUrl
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append(findingsMarker(headSha)).append("\n\n");
        sb.append("## 指摘事項\n");
        appendIncrementalScopeNote(sb, incrementalPreviousHeadSha);

        List<Finding> findings = output.findings();
        if (findings.isEmpty()) {
            sb.append("特に指摘事項はありませんでした。\n");
        } else {
            // 表示件数上限で切り詰める前に、確信度の高い指摘(error)が埋もれないようseverity優先度で並べ替える
            // (Stream.sortedは安定ソートのため、同severity内の順序はLLM出力順を維持する)。
            List<Finding> sorted = findings.stream()
                    .sorted(Comparator.comparingInt(f -> severityRank(f.normalizedSeverity())))
                    .toList();
            boolean truncated = sorted.size() > maxComments;
            List<Finding> displayed = truncated ? sorted.subList(0, maxComments) : sorted;
            for (Finding f : displayed) {
                String location = formatLocation(f, fileBlobBaseUrl);
                // "**[severity] [text](url)**" のように太字を閉じずに隣接した角括弧をまたぐと、
                // GitBucketのMarkdownレンダラーがリンクの表示テキストをURLそのものにすり替えてしまう
                // (実機検証で確認済み)。severityとlocationを別々の太字スパンに分けて回避する。
                sb.append("- **[").append(f.normalizedSeverity()).append("]** **").append(location).append("**");
                if (!isBlank(f.perspective())) {
                    sb.append(" (観点: ").append(f.perspective()).append(")");
                }
                sb.append("\n  ").append(f.comment()).append("\n");
                appendSnippetIfAvailable(sb, diffIndex, f);
            }
            if (truncated) {
                sb.append("\n(他 ").append(sorted.size() - maxComments).append(" 件の指摘は表示上限のため省略されました)\n");
            }
        }
        sb.append('\n');
        appendFooter(sb, headSha, modelName, referencedFiles);
        return sb.toString();
    }

    /** メンション応答(追質問/追レビュー)の回答コメント本文を組み立てる。 */
    public static String formatMentionReply(
            MentionReplyOutput output,
            String headSha,
            String modelName,
            List<ReferencedFile> referencedFiles,
            long triggerCommentId,
            String triggerUserLogin
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append(mentionReplyMarker(triggerCommentId)).append("\n\n");
        sb.append("## 回答");
        if (!isBlank(triggerUserLogin)) {
            sb.append(" (@").append(triggerUserLogin).append(" さんへ)");
        }
        sb.append('\n');
        sb.append(isBlank(output.answer()) ? "(回答なし)" : output.answer()).append("\n\n");
        appendFooter(sb, headSha, modelName, referencedFiles);
        return sb.toString();
    }

    private static void appendIncrementalScopeNote(StringBuilder sb, String incrementalPreviousHeadSha) {
        if (incrementalPreviousHeadSha != null) {
            sb.append("> 本レビューは前回レビュー(head: `").append(shortSha(incrementalPreviousHeadSha))
                    .append("`)以降の差分のみが対象です。\n\n");
        }
    }

    /** サマリ・指摘事項・メンション応答の全コメント共通のフッター(モデル名/head/参照したコンテキストファイル)を付記する。 */
    private static void appendFooter(StringBuilder sb, String headSha, String modelName, List<ReferencedFile> referencedFiles) {
        sb.append("---\n");
        sb.append("モデル: `").append(modelName).append("` / head: `").append(shortSha(headSha)).append("`");
        sb.append('\n');
        if (!referencedFiles.isEmpty()) {
            sb.append("\n## 参照したコンテキストファイル\n");
            for (ReferencedFile f : referencedFiles) {
                sb.append("- ").append(f.path()).append(" (").append(f.kind().label()).append(")\n");
            }
        }
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

    /**
     * fileBlobBaseUrl({@code {baseUrl}/{owner}/{repo}/blob/{headSha}} 形式、末尾スラッシュなし)が
     * 与えられている場合はGitBucketのファイル表示ページへのMarkdownリンクにし、無ければプレーンテキストのまま返す。
     */
    private static String formatLocation(Finding f, String fileBlobBaseUrl) {
        String plain = f.line() != null ? "%s:%d".formatted(f.file(), f.line()) : String.valueOf(f.file());
        if (isBlank(fileBlobBaseUrl) || isBlank(f.file())) {
            return plain;
        }
        String url = fileBlobBaseUrl + "/" + encodePath(f.file()) + (f.line() != null ? "#L" + f.line() : "");
        return "[%s](%s)".formatted(plain, url);
    }

    private static String encodePath(String path) {
        return Arrays.stream(path.split("/"))
                .map(segment -> URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20"))
                .collect(Collectors.joining("/"));
    }

    private static int severityRank(String normalizedSeverity) {
        return switch (normalizedSeverity) {
            case "error" -> 0;
            case "warning" -> 1;
            default -> 2;
        };
    }

    private static String shortSha(String sha) {
        return sha.length() > 10 ? sha.substring(0, 10) : sha;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
