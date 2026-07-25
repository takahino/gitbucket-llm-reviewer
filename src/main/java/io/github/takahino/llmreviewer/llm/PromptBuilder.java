package io.github.takahino.llmreviewer.llm;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import io.github.takahino.llmreviewer.config.RepoReviewConfig;
import io.github.takahino.llmreviewer.git.DiffResult;
import io.github.takahino.llmreviewer.rag.RagSearchResult;
import io.github.takahino.llmreviewer.rag.RetrievedChunk;
import io.github.takahino.llmreviewer.review.FindingValidator;
import io.github.takahino.llmreviewer.scm.model.PullRequest;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** レビュー用プロンプト(システム/ユーザー/追加ファイル/強制確定メッセージ)を組み立てる。 */
public class PromptBuilder {

    private final int maxAdditionalFiles;

    public PromptBuilder(int maxAdditionalFiles) {
        this.maxAdditionalFiles = maxAdditionalFiles;
    }

    public ChatMessage systemMessage(String language) {
        String content = """
                あなたは経験豊富なソフトウェアエンジニアとして、プルリクエストのコードレビューを行います。
                出力は必ず次のJSONスキーマに従う1つのJSONオブジェクトのみとし、説明文やコードフェンスは付けないでください。

                {
                  "status": "complete または need_more_context",
                  "requestedFiles": [ { "path": "対象ファイルの相対パス", "reason": "必要な理由" } ],
                  "summary": "変更内容のサマリ(status=completeの場合は必須。Markdown形式の文字列)",
                  "findings": [
                    { "file": "相対パス", "line": 変更後(new)ファイル基準の行番号(不明ならnull), "severity": "error"|"warning"|"info",
                      "perspective": "対応する観点", "comment": "指摘内容と修正提案" }
                  ]
                }

                summary・comment・perspective等、レビュー内容を説明する自然文はすべて「%s」で記述してください
                (status・severityなどJSONのキー名や固定値の記述は対象外です)。

                summaryフィールドは、レビュアーがdiffを読まなくても変更内容を把握できるだけの具体性を持たせ、
                次の方針でMarkdown形式で記述してください:
                - 変更されたファイルごとに `### path/to/File.java` のような見出しを立て、「何を」「なぜ」「どのように」変更したかを説明する。
                - 「〜を修正しました」のような曖昧な一文で終わらせず、ロジック・シグネチャ・設定値が変更前後でどう変わったかを具体的に書く。
                - 挙動やAPI・シグネチャに影響する重要な変更については、該当箇所の実際のコード片をコードブロックとして
                  引用し、要点となる行を示すこと(diffの丸写しではなく、要点を絞った短い引用でよい)。
                - 軽微な変更(フォーマットのみ、コメント追加のみ等)は簡潔にまとめてよい。重要度の高い変更を優先して詳しく書くこと。
                - JSON文字列として出力するため、コードブロック内の改行は\\nでエスケープし、二重引用符は正しくエスケープすること。

                findingsを作成する際は、次のレビュー品質に関する方針に従ってください:
                - 指摘は基本的に本PRのdiffで変更された内容に基づくものとしてください。レビュー観点が明示的に
                  変更箇所を越えた全体設計・既存コードのレビューを求めている場合を除き、diffに現れない箇所への
                  指摘は行わないでください。
                - 各指摘の comment には根拠を含めてください。diff上の具体的な変更(該当する記号名・条件式・
                  行の内容など)を引用し、なぜ問題だと考えたのかが読み手に伝わるように書いてください。
                - severity は確信度に応じて区別してください: 明確なバグ・セキュリティ上の問題で確信度が高いものは
                  "error"、改善を推奨する程度のものは "warning"、断定できない提案やnitpick(些末なスタイル・
                  個人の好みレベルの指摘)は "info" としてください。断定できない指摘を "error"/"warning" にしないこと。
                - フォーマットのみ・個人の好みレベルの命名など、些末な指摘は極力抑制するか "info" に格下げしてください。
                - 各指摘の perspective は、実際に適用された「## レビュー観点」セクションに列挙された項目の文言と
                  対応させてください。どの観点にも対応しない指摘は行わないでください。
                - diffや提供された情報だけでは確信が持てない場合は、無理に指摘を作らず、後述の need_more_context を
                  使って必要な情報を追加取得してください。

                diffだけでは判断できない場合(呼び出し先の実装・型定義・既存の命名規約などの確認が必要な場合)は、
                status を "need_more_context" とし、requestedFiles で確認したいファイルを最大%d件まで要求してください。
                要求したファイルの内容は次のメッセージで提供されるので、それを踏まえて再度同じスキーマで出力してください。
                """.formatted(languageLabel(language), maxAdditionalFiles);
        return new SystemMessage(content);
    }

    /** .review.yml の language 設定(未指定なら"ja")をプロンプトに埋め込む表記に変換する。 */
    private static String languageLabel(String language) {
        if (isBlank(language) || "ja".equalsIgnoreCase(language)) {
            return "日本語";
        }
        return language;
    }

    public ChatMessage initialUserMessage(
            PullRequest pr,
            RepoReviewConfig repoConfig,
            List<RepoReviewConfig.PerspectiveGroup> perspectiveGroups,
            List<String> repositoryFilePaths,
            Map<String, String> contextFiles,
            Map<String, String> perspectiveContextFiles,
            RagSearchResult ragResult,
            DiffResult diff,
            String incrementalPreviousHeadSha,
            Map<String, String> fullFileContext
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append("## プルリクエスト情報\n");
        sb.append("- タイトル: ").append(pr.title()).append('\n');
        sb.append("- 本文:\n").append(isBlank(pr.body()) ? "(なし)" : pr.body()).append("\n\n");

        sb.append("## レビュー観点\n");
        for (RepoReviewConfig.PerspectiveGroup group : perspectiveGroups) {
            sb.append("### ").append(group.label())
                    .append(" (対象: ").append(String.join(", ", group.matchedFiles())).append(")\n");
            for (RepoReviewConfig.PerspectiveEntry entry : group.perspectives()) {
                sb.append("- ").append(entry.text());
                List<String> resolvedPaths = entry.resolvedContextPaths();
                if (!resolvedPaths.isEmpty()) {
                    sb.append(" (参照ドキュメント: ").append(String.join(", ", resolvedPaths)).append(")");
                }
                sb.append('\n');
            }
        }
        sb.append('\n');

        if (!repositoryFilePaths.isEmpty()) {
            sb.append("## リポジトリのファイル一覧(追加確認したい場合の参考。全").append(repositoryFilePaths.size()).append("件)\n");
            repositoryFilePaths.forEach(p -> sb.append("- ").append(p).append('\n'));
            sb.append('\n');
        }

        if (!contextFiles.isEmpty()) {
            sb.append("## 常時提供されるコンテキストファイル\n");
            contextFiles.forEach((path, content) ->
                    sb.append("### ").append(path).append("\n```\n").append(content).append("\n```\n\n"));
        }

        if (!perspectiveContextFiles.isEmpty()) {
            sb.append("## 観点別の追加コンテキスト(上記レビュー観点の「参照ドキュメント」の内容)\n");
            perspectiveContextFiles.forEach((path, content) ->
                    sb.append("### ").append(path).append("\n```\n").append(content).append("\n```\n\n"));
        }

        appendRagSection(sb, "関連コード候補(ベクトル検索による自動抽出。参考情報であり正確性は保証されません。"
                        + "不足があれば requestedFiles で該当ファイルの取得を要求してください)",
                ragResult.relatedCode());
        appendRagSection(sb, "関連コーディング規約抜粋(ベクトル検索による自動抽出。参考情報です)",
                ragResult.knowledgeBase());

        if (!fullFileContext.isEmpty()) {
            sb.append("## 変更ファイルの全文コンテキスト(オプトイン機能。変更ファイル本体・推測されるテストファイル・"
                    + "同一ディレクトリの関連ファイルのnew側全文)\n");
            fullFileContext.forEach((path, content) ->
                    sb.append("### ").append(path).append("\n```\n").append(content).append("\n```\n\n"));
        }

        sb.append("## 差分(unified diff)\n");
        if (incrementalPreviousHeadSha != null) {
            sb.append("(注意: これは前回レビュー(head: ").append(shortSha(incrementalPreviousHeadSha))
                    .append(")以降の増分差分です。PR全体の差分ではありません)\n");
        }
        if (diff.truncated()) {
            sb.append("(注意: 差分が上限文字数を超えたため切り詰められています)\n");
        }
        sb.append("```diff\n").append(diff.diffText()).append("\n```\n");

        return new UserMessage(sb.toString());
    }

    public ChatMessage assistantMessage(String rawJsonContent) {
        return new AiMessage(rawJsonContent);
    }

    /** findingsのfile/lineが実在しないと判定された場合に、修正を促すメッセージを組み立てる。 */
    public ChatMessage findingsCorrectionMessage(List<FindingValidator.ValidationIssue> issues) {
        StringBuilder sb = new StringBuilder(
                "## findingsの内容に問題があります\n"
                        + "次の指摘は file/line がリポジトリ上の実際の内容と一致しないため、そのままでは採用できません。\n");
        for (FindingValidator.ValidationIssue issue : issues) {
            sb.append("- ").append(issue.reason())
                    .append(" (comment: ").append(issue.finding().comment()).append(")\n");
        }
        sb.append("\n該当する指摘については、正しい file/line に修正するか、"
                + "確信が持てない場合は findings から除外してください。"
                + "それ以外の指摘はそのままで構いません。同じJSONスキーマで出力し直してください。\n");
        return new UserMessage(sb.toString());
    }

    public ChatMessage additionalFilesMessage(Map<String, Optional<String>> resolvedFiles) {
        StringBuilder sb = new StringBuilder("## 要求されたファイルの内容\n");
        resolvedFiles.forEach((path, content) -> {
            if (content.isPresent()) {
                sb.append("### ").append(path).append("\n```\n").append(content.get()).append("\n```\n\n");
            } else {
                sb.append("### ").append(path)
                        .append("\n(このパスは見つかりませんでした。存在するパスの中から近いものを再確認してください)\n\n");
            }
        });
        sb.append("上記を踏まえて、同じJSONスキーマで出力してください。\n");
        return new UserMessage(sb.toString());
    }

    public ChatMessage forceCompleteMessage() {
        return new UserMessage(
                "追加のファイル提供は上限に達しました。現時点で得られている情報のみで、" +
                        "status を \"complete\" として最終的なレビュー結果を同じJSONスキーマで出力してください。");
    }

    private static void appendRagSection(StringBuilder sb, String heading, List<RetrievedChunk> chunks) {
        if (chunks.isEmpty()) {
            return;
        }
        sb.append("## ").append(heading).append('\n');
        for (RetrievedChunk chunk : chunks) {
            sb.append("### ").append(chunk.sourcePath())
                    .append(" (score=").append("%.2f".formatted(chunk.score())).append(")\n")
                    .append("```\n").append(chunk.content()).append("\n```\n\n");
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String shortSha(String sha) {
        return sha.length() > 10 ? sha.substring(0, 10) : sha;
    }
}
