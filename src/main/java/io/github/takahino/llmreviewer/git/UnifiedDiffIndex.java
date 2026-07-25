package io.github.takahino.llmreviewer.git;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * unified diffテキストをパースし、ファイル毎のhunk構造(新側行番号付き)を保持する。
 * GitBucketにはPRのdiff行への直接コメントAPIが無いため、指摘箇所の周辺コードを
 * Issueコメント内に引用するための「疑似インライン化」に使う。
 */
public final class UnifiedDiffIndex {

    private static final Pattern DIFF_GIT_LINE = Pattern.compile("^diff --git a/(.*) b/(.*)$");
    private static final Pattern HUNK_HEADER = Pattern.compile("^@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@.*$");

    private record HunkLine(int newLineNo, char marker, String text) {
    }

    private final Map<String, List<List<HunkLine>>> hunksByFile;

    private UnifiedDiffIndex(Map<String, List<List<HunkLine>>> hunksByFile) {
        this.hunksByFile = hunksByFile;
    }

    public static UnifiedDiffIndex parse(String diffText) {
        Map<String, List<List<HunkLine>>> hunksByFile = new LinkedHashMap<>();
        String currentFile = null;
        List<HunkLine> currentHunk = null;
        int newLineCounter = 0;

        for (String line : diffText.lines().toList()) {
            Matcher gitMatcher = DIFF_GIT_LINE.matcher(line);
            if (gitMatcher.matches()) {
                currentFile = gitMatcher.group(2);
                currentHunk = null;
                hunksByFile.putIfAbsent(currentFile, new ArrayList<>());
                continue;
            }
            Matcher hunkMatcher = HUNK_HEADER.matcher(line);
            if (hunkMatcher.matches()) {
                newLineCounter = Integer.parseInt(hunkMatcher.group(1));
                currentHunk = new ArrayList<>();
                if (currentFile != null) {
                    hunksByFile.get(currentFile).add(currentHunk);
                }
                continue;
            }
            if (currentHunk == null || line.startsWith("\\")) {
                continue; // hunk外の行、または "\ No newline at end of file"
            }
            if (line.startsWith("+")) {
                currentHunk.add(new HunkLine(newLineCounter, '+', line.substring(1)));
                newLineCounter++;
            } else if (line.startsWith("-")) {
                currentHunk.add(new HunkLine(-1, '-', line.substring(1)));
            } else if (line.startsWith(" ")) {
                currentHunk.add(new HunkLine(newLineCounter, ' ', line.substring(1)));
                newLineCounter++;
            }
        }
        return new UnifiedDiffIndex(hunksByFile);
    }

    /** diffに含まれる変更ファイルのパス一覧(新側=b/パス、diff出現順)を返す。 */
    public List<String> changedFiles() {
        return List.copyOf(hunksByFile.keySet());
    }

    /** 指定ファイルの新側行番号 newLine を含むhunkから、前後 contextLines 行を引用テキストとして返す。 */
    public Optional<String> snippet(String file, int newLine, int contextLines) {
        if (newLine <= 0) {
            return Optional.empty();
        }
        List<List<HunkLine>> hunks = hunksByFile.get(file);
        if (hunks == null) {
            return Optional.empty();
        }
        for (List<HunkLine> hunk : hunks) {
            for (int i = 0; i < hunk.size(); i++) {
                if (hunk.get(i).newLineNo() == newLine) {
                    int from = Math.max(0, i - contextLines);
                    int to = Math.min(hunk.size(), i + contextLines + 1);
                    StringBuilder sb = new StringBuilder();
                    for (int j = from; j < to; j++) {
                        HunkLine hl = hunk.get(j);
                        sb.append(hl.marker()).append(hl.text()).append('\n');
                    }
                    return Optional.of(sb.toString().stripTrailing());
                }
            }
        }
        return Optional.empty();
    }
}
