package io.github.takahino.llmreviewer.review;

import io.github.takahino.llmreviewer.git.RepositoryReader;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * (オプトイン機能) 変更ファイルのnew側全文・対応するテストファイル(命名ヒューリスティック)・
 * 同一ディレクトリの関連ファイルをまとめて収集し、diffだけに頼らない第1パスのコンテキストとして
 * LLMに提供する。プロンプトサイズ・LLM呼び出しコストが増えるため、
 * {@code review.fullFileContextEnabled: true} の場合のみ呼び出される想定。
 */
public class FullFileContextCollector {

    private static final Logger LOGGER = Logger.getLogger(FullFileContextCollector.class.getName());
    private static final int SIBLING_LIMIT_PER_FILE = 5;
    private static final List<String> TEST_SUFFIXES = List.of("Test", "Tests", "Spec");

    private final RepositoryReader repositoryReader;
    private final int maxFiles;
    private final int maxFileChars;

    public FullFileContextCollector(RepositoryReader repositoryReader, int maxFiles, int maxFileChars) {
        this.repositoryReader = repositoryReader;
        this.maxFiles = maxFiles;
        this.maxFileChars = maxFileChars;
    }

    /** path -> new側全文(切り詰め済み)。挿入順は「変更ファイル本体→推測テストファイル→同ディレクトリ関連ファイル」。 */
    public Map<String, String> collect(
            String owner, String repo, String headSha, List<String> changedFiles, List<String> repositoryFileTree
    ) {
        Map<String, String> result = new LinkedHashMap<>();
        Set<String> fileTreeSet = new LinkedHashSet<>(repositoryFileTree);

        for (String changedFile : changedFiles) {
            if (result.size() >= maxFiles) {
                break;
            }
            addFile(result, owner, repo, headSha, changedFile);

            guessTestFile(changedFile, fileTreeSet)
                    .filter(path -> result.size() < maxFiles)
                    .ifPresent(path -> addFile(result, owner, repo, headSha, path));

            for (String sibling : siblingFiles(changedFile, fileTreeSet)) {
                if (result.size() >= maxFiles) {
                    break;
                }
                addFile(result, owner, repo, headSha, sibling);
            }
        }
        return result;
    }

    private void addFile(Map<String, String> result, String owner, String repo, String headSha, String path) {
        if (path == null || result.containsKey(path)) {
            return;
        }
        try {
            repositoryReader.readFile(owner, repo, headSha, path).ifPresent(content -> result.put(path, truncate(content)));
        } catch (RuntimeException e) {
            LOGGER.log(Level.FINE, "全文コンテキスト取得に失敗したためスキップします: " + path, e);
        }
    }

    private String truncate(String content) {
        if (content.length() <= maxFileChars) {
            return content;
        }
        return content.substring(0, maxFileChars) + "\n...(以降は文字数上限のため切り詰め)";
    }

    /**
     * {@code Foo.java -> FooTest.java} 等の命名ヒューリスティックで候補パスを組み立て、
     * リポジトリのファイルツリーに実在するものだけを返す(存在しない推測パスをLLMに渡して混乱させないため)。
     */
    private static Optional<String> guessTestFile(String changedFile, Set<String> fileTreeSet) {
        int dot = changedFile.lastIndexOf('.');
        int slash = changedFile.lastIndexOf('/');
        if (dot < 0 || dot < slash) {
            return Optional.empty();
        }
        String base = changedFile.substring(0, dot);
        String ext = changedFile.substring(dot);
        String baseName = slash < 0 ? base : base.substring(slash + 1);
        if (TEST_SUFFIXES.stream().anyMatch(baseName::endsWith)) {
            return Optional.empty(); // 変更対象自体が既にテストファイル
        }

        List<String> candidates = new ArrayList<>();
        for (String suffix : TEST_SUFFIXES) {
            candidates.add(base + suffix + ext);
        }
        String testDirBase = base.replaceFirst("/main/", "/test/");
        if (!testDirBase.equals(base)) {
            for (String suffix : TEST_SUFFIXES) {
                candidates.add(testDirBase + suffix + ext);
            }
        }
        return candidates.stream().filter(fileTreeSet::contains).findFirst();
    }

    /** 変更ファイルと同一ディレクトリに属する他ファイル(自分自身は除く)を、上限件数まで返す。 */
    private static List<String> siblingFiles(String changedFile, Set<String> fileTreeSet) {
        String dir = dirOf(changedFile);
        return fileTreeSet.stream()
                .filter(path -> !path.equals(changedFile))
                .filter(path -> dirOf(path).equals(dir))
                .limit(SIBLING_LIMIT_PER_FILE)
                .toList();
    }

    private static String dirOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash + 1);
    }
}
