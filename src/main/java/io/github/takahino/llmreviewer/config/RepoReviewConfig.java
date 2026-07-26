package io.github.takahino.llmreviewer.config;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * リポジトリ側の観点設定(リポジトリルートの .review.yml)。
 * モノレポ対応として paths に glob 毎の追加観点を持てる。
 */
public record RepoReviewConfig(
        String language,
        @JsonDeserialize(using = PerspectiveEntryListDeserializer.class) List<PerspectiveEntry> perspectives,
        Map<String, PathConfig> paths,
        List<String> exclude,
        List<String> contextFiles,
        List<String> knowledgeBase,
        Integer maxComments
) {
    /** .review.yml と同階層に置く追加コンテキストファイル(Markdown)の格納フォルダ。 */
    public static final String REVIEW_CONTEXT_DIR = ".review/";

    public RepoReviewConfig {
        language = (language == null || language.isBlank()) ? "ja" : language;
        perspectives = perspectives == null ? List.of() : List.copyOf(perspectives);
        paths = paths == null ? Map.of() : Map.copyOf(paths);
        exclude = exclude == null ? List.of() : List.copyOf(exclude);
        contextFiles = contextFiles == null ? List.of() : List.copyOf(contextFiles);
        knowledgeBase = knowledgeBase == null ? List.of() : List.copyOf(knowledgeBase);
        maxComments = maxComments == null ? 10 : maxComments;
    }

    /**
     * 1つの観点(テキスト)と、それに紐づく `.review/` 配下の追加コンテキストファイル(任意)。
     * YAML上はスカラー文字列(contextなし)、または `perspective`/`context` を持つマッピングのどちらでも記述できる。
     */
    public record PerspectiveEntry(String text, List<String> context) {
        public PerspectiveEntry {
            context = context == null ? List.of() : List.copyOf(context);
        }

        /** context の各ファイル名を `.review/` 配下の相対パスに解決する。 */
        public List<String> resolvedContextPaths() {
            return context.stream().map(f -> REVIEW_CONTEXT_DIR + f).toList();
        }
    }

    /** モノレポの1フォルダ(glob)に対する追加観点・追加コーディング規約設定。 */
    public record PathConfig(
            @JsonDeserialize(using = PerspectiveEntryListDeserializer.class) List<PerspectiveEntry> perspectives,
            Boolean inherit,
            List<String> knowledgeBase
    ) {
        public PathConfig {
            perspectives = perspectives == null ? List.of() : List.copyOf(perspectives);
            inherit = inherit == null ? Boolean.TRUE : inherit;
            knowledgeBase = knowledgeBase == null ? List.of() : List.copyOf(knowledgeBase);
        }
    }

    /** 変更ファイル一覧に対して適用される観点をグループ化して解決する(モノレポのパス毎観点対応)。 */
    public record PerspectiveGroup(String label, List<PerspectiveEntry> perspectives, List<String> matchedFiles) {
    }

    /** 1つのpath(glob)設定が変更ファイル一覧にマッチした結果。resolveGroupsFor/resolveKnowledgeBaseForで共有する。 */
    private record PathMatch(String glob, PathConfig config, List<String> matchedFiles) {
    }

    /** pathsの各globを変更ファイル一覧に照らし、マッチしたもの(空マッチは除く)を一覧化する。 */
    private List<PathMatch> matchPaths(List<String> changedFilePaths) {
        List<PathMatch> matches = new ArrayList<>();
        for (Map.Entry<String, PathConfig> entry : paths.entrySet()) {
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + entry.getKey());
            List<String> matchedFiles = changedFilePaths.stream()
                    .filter(f -> matcher.matches(Path.of(f)))
                    .toList();
            if (!matchedFiles.isEmpty()) {
                matches.add(new PathMatch(entry.getKey(), entry.getValue(), matchedFiles));
            }
        }
        return matches;
    }

    /** inherit=falseのpathにマッチしたファイルは、共通観点・共通knowledgeBaseを継承しない。 */
    private static Set<String> excludedFromCommon(List<PathMatch> matches) {
        Set<String> excluded = new HashSet<>();
        for (PathMatch match : matches) {
            if (!match.config().inherit()) {
                excluded.addAll(match.matchedFiles());
            }
        }
        return excluded;
    }

    public List<PerspectiveGroup> resolveGroupsFor(List<String> changedFilePaths) {
        List<PathMatch> matches = matchPaths(changedFilePaths);
        Set<String> excludedFromCommon = excludedFromCommon(matches);

        List<PerspectiveGroup> groups = new ArrayList<>();
        List<String> commonFiles = changedFilePaths.stream().filter(f -> !excludedFromCommon.contains(f)).toList();
        if (!perspectives.isEmpty() && !commonFiles.isEmpty()) {
            groups.add(new PerspectiveGroup("共通", perspectives, commonFiles));
        }
        for (PathMatch match : matches) {
            if (!match.config().perspectives().isEmpty()) {
                groups.add(new PerspectiveGroup(match.glob(), match.config().perspectives(), match.matchedFiles()));
            }
        }
        return groups;
    }

    /**
     * 変更ファイル一覧に対して適用されるコーディング規約(knowledgeBase)のパスを解決する。
     * resolveGroupsFor と同じ「pathにマッチしたファイルはinherit=falseなら共通を継承しない」ルールを踏襲する。
     */
    public List<String> resolveKnowledgeBaseFor(List<String> changedFilePaths) {
        List<PathMatch> matches = matchPaths(changedFilePaths);
        Set<String> excludedFromCommon = excludedFromCommon(matches);

        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (PathMatch match : matches) {
            result.addAll(match.config().knowledgeBase());
        }
        boolean hasCommonScopeFiles = changedFilePaths.stream().anyMatch(f -> !excludedFromCommon.contains(f));
        if (hasCommonScopeFiles) {
            result.addAll(knowledgeBase);
        }
        return List.copyOf(result);
    }

    /**
     * .review.yml が存在しない/パース失敗した場合のフォールバック設定。
     * perspectives が空のため、呼び出し元(ReviewOrchestrator)はこの設定を検出するとfindings(指摘事項)の
     * 生成をスキップし、変更サマリのみを生成する(summary-onlyモード)。
     */
    public static RepoReviewConfig defaultConfig() {
        return new RepoReviewConfig("ja", List.of(), Map.of(), List.of(), List.of(), List.of(), 10);
    }
}
