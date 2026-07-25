package io.github.takahino.llmreviewer.git;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * unified diffを文字数上限で切り詰める。単純な先頭カットだとファイルの途中で
 * 千切れて後半ファイルの変更が破損した形でLLMに渡ってしまうため、
 * "diff --git a/... b/..." 行をファイル境界として検出し、ファイル単位の塊ごと
 * 上限に収まる範囲まで積み上げる。
 */
public final class DiffTruncator {

    private static final Pattern DIFF_GIT_LINE = Pattern.compile("^diff --git a/.* b/.*$");

    private DiffTruncator() {
    }

    public static DiffResult truncate(String diff, int maxChars) {
        if (diff.length() <= maxChars) {
            return new DiffResult(diff, false);
        }
        List<String> chunks = splitByFile(diff);
        if (chunks.size() <= 1) {
            // ファイル境界を検出できない(diff --gitが1件以下)場合は従来通り先頭カットにフォールバックする
            return new DiffResult(diff.substring(0, maxChars), true);
        }
        StringBuilder sb = new StringBuilder();
        for (String chunk : chunks) {
            if (sb.length() + chunk.length() > maxChars) {
                break;
            }
            sb.append(chunk);
        }
        if (sb.isEmpty()) {
            // 先頭の塊(前置きコメント等)すら上限に収まらない極端なケースは先頭カットで内容を残す
            return new DiffResult(chunks.get(0).substring(0, maxChars), true);
        }
        return new DiffResult(sb.toString(), true);
    }

    /**
     * "diff --git"行を境界として、それ以前の前置き部分(あれば)とファイル毎の塊に分割する。
     * {@link DiffBatcher} がバッチ分割時のファイル境界検出に再利用するため package-private。
     */
    static List<String> splitByFile(String diff) {
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : diff.lines().toList()) {
            if (DIFF_GIT_LINE.matcher(line).matches() && !current.isEmpty()) {
                chunks.add(current.toString());
                current = new StringBuilder();
            }
            current.append(line).append('\n');
        }
        if (!current.isEmpty()) {
            chunks.add(current.toString());
        }
        return chunks;
    }
}
