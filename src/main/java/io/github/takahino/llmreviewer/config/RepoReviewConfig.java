package io.github.takahino.llmreviewer.config;

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
        List<String> perspectives,
        Map<String, PathConfig> paths,
        List<String> exclude,
        List<String> contextFiles,
        List<String> knowledgeBase,
        Integer maxComments
) {
    public RepoReviewConfig {
        language = (language == null || language.isBlank()) ? "ja" : language;
        perspectives = perspectives == null ? List.of() : List.copyOf(perspectives);
        paths = paths == null ? Map.of() : Map.copyOf(paths);
        exclude = exclude == null ? List.of() : List.copyOf(exclude);
        contextFiles = contextFiles == null ? List.of() : List.copyOf(contextFiles);
        knowledgeBase = knowledgeBase == null ? List.of() : List.copyOf(knowledgeBase);
        maxComments = maxComments == null ? 10 : maxComments;
    }

    /** モノレポの1フォルダ(glob)に対する追加観点・追加コーディング規約設定。 */
    public record PathConfig(List<String> perspectives, Boolean inherit, List<String> knowledgeBase) {
        public PathConfig {
            perspectives = perspectives == null ? List.of() : List.copyOf(perspectives);
            inherit = inherit == null ? Boolean.TRUE : inherit;
            knowledgeBase = knowledgeBase == null ? List.of() : List.copyOf(knowledgeBase);
        }
    }

    /** 変更ファイル一覧に対して適用される観点をグループ化して解決する(モノレポのパス毎観点対応)。 */
    public record PerspectiveGroup(String label, List<String> perspectives, List<String> matchedFiles) {
    }

    public List<PerspectiveGroup> resolveGroupsFor(List<String> changedFilePaths) {
        List<PerspectiveGroup> pathGroups = new ArrayList<>();
        Set<String> excludedFromCommon = new HashSet<>();

        for (Map.Entry<String, PathConfig> entry : paths.entrySet()) {
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + entry.getKey());
            List<String> matchedFiles = changedFilePaths.stream()
                    .filter(f -> matcher.matches(Path.of(f)))
                    .toList();
            if (matchedFiles.isEmpty()) {
                continue;
            }
            if (!entry.getValue().inherit()) {
                excludedFromCommon.addAll(matchedFiles);
            }
            if (!entry.getValue().perspectives().isEmpty()) {
                pathGroups.add(new PerspectiveGroup(entry.getKey(), entry.getValue().perspectives(), matchedFiles));
            }
        }

        List<PerspectiveGroup> groups = new ArrayList<>();
        List<String> commonFiles = changedFilePaths.stream().filter(f -> !excludedFromCommon.contains(f)).toList();
        if (!perspectives.isEmpty() && !commonFiles.isEmpty()) {
            groups.add(new PerspectiveGroup("共通", perspectives, commonFiles));
        }
        groups.addAll(pathGroups);
        return groups;
    }

    /**
     * 変更ファイル一覧に対して適用されるコーディング規約(knowledgeBase)のパスを解決する。
     * resolveGroupsFor と同じ「pathにマッチしたファイルはinherit=falseなら共通を継承しない」ルールを踏襲する。
     */
    public List<String> resolveKnowledgeBaseFor(List<String> changedFilePaths) {
        Set<String> excludedFromCommon = new HashSet<>();
        LinkedHashSet<String> result = new LinkedHashSet<>();

        for (Map.Entry<String, PathConfig> entry : paths.entrySet()) {
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + entry.getKey());
            List<String> matchedFiles = changedFilePaths.stream()
                    .filter(f -> matcher.matches(Path.of(f)))
                    .toList();
            if (matchedFiles.isEmpty()) {
                continue;
            }
            if (!entry.getValue().inherit()) {
                excludedFromCommon.addAll(matchedFiles);
            }
            result.addAll(entry.getValue().knowledgeBase());
        }

        boolean hasCommonScopeFiles = changedFilePaths.stream().anyMatch(f -> !excludedFromCommon.contains(f));
        if (hasCommonScopeFiles) {
            result.addAll(knowledgeBase);
        }
        return List.copyOf(result);
    }

    public static RepoReviewConfig defaultConfig() {
        return new RepoReviewConfig(
                "ja",
                List.of(
                        "セキュリティ上の懸念(インジェクション、認可漏れ、機密情報の露出)",
                        "既存コードとの命名・設計の一貫性",
                        "バグ・エッジケースの見落とし"
                ),
                Map.of(),
                List.of(),
                List.of(),
                List.of(),
                10
        );
    }
}
