package io.github.takahino.llmreviewer.git;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PR全体の生unified diffを、1バッチ(1回のLLMリクエスト)あたりの文字数上限を尊重しつつ
 * ファイル境界("diff --git a/... b/...")単位でバッチに分割する。
 *
 * {@link DiffTruncator} が「上限を超えた分を切り捨てる」のに対し、こちらは
 * 「上限を超えた分を複数バッチに分けて漏らさず処理する」ためのもの。
 * バッチ数が maxBatches を超える場合のみ、超過分のファイルを {@code skippedFiles} として
 * 報告する(黙って消さず、呼び出し側がユーザーに明示できるようにする)。
 */
public final class DiffBatcher {

    private static final Pattern DIFF_GIT_LINE = Pattern.compile("^diff --git a/(?:.*) b/(.*)$");

    private DiffBatcher() {
    }

    /** 1バッチ分。changedFiles はこのバッチに含まれる b/ パス(diff出現順、重複なし)。 */
    public record Batch(DiffResult diff, List<String> changedFiles) {
    }

    /**
     * batches は先頭から1バッチ目、2バッチ目…の順。
     * skippedFiles は maxBatches 超過により今回対象外となったファイル(元のdiff出現順)。
     */
    public record Result(List<Batch> batches, List<String> skippedFiles) {
    }

    public static Result split(String diffText, int maxCharsPerBatch, int maxBatches) {
        List<String> chunks = DiffTruncator.splitByFile(diffText);
        List<Batch> allBatches = pack(chunks, maxCharsPerBatch);

        if (allBatches.size() <= maxBatches) {
            return new Result(allBatches, List.of());
        }
        List<Batch> accepted = allBatches.subList(0, maxBatches);
        Set<String> skipped = new LinkedHashSet<>();
        allBatches.subList(maxBatches, allBatches.size())
                .forEach(batch -> skipped.addAll(batch.changedFiles()));
        return new Result(List.copyOf(accepted), List.copyOf(skipped));
    }

    /** ファイル境界チャンクを、文字数上限に収まる範囲で束ねてバッチ列を作る(バッチ数はここでは無制限)。 */
    private static List<Batch> pack(List<String> chunks, int maxCharsPerBatch) {
        List<Batch> batches = new ArrayList<>();
        StringBuilder currentText = new StringBuilder();
        List<String> currentFiles = new ArrayList<>();
        String preamble = "";

        for (String chunk : chunks) {
            String file = extractFilePath(chunk);
            if (file == null) {
                // "diff --git"行にマッチしない先頭の前置き部分(ApiDiffProviderの注意書き等)。
                // ファイルとしてはカウントせず、最初のバッチのテキスト先頭に結合する。
                preamble = chunk;
                continue;
            }
            if (chunk.length() > maxCharsPerBatch) {
                // 1ファイル単体が上限超過: それまでの蓄積を確定させ、このファイルは
                // DiffTruncatorの先頭カットを適用した単独バッチ(truncated=true)にする。
                if (!currentFiles.isEmpty()) {
                    batches.add(finalize(preamble, currentText, currentFiles));
                    preamble = "";
                    currentText = new StringBuilder();
                    currentFiles = new ArrayList<>();
                }
                String combined = preamble + chunk;
                preamble = "";
                String truncatedText = combined.length() > maxCharsPerBatch
                        ? combined.substring(0, maxCharsPerBatch) : combined;
                batches.add(new Batch(new DiffResult(truncatedText, true), List.of(file)));
                continue;
            }
            int prefixLen = preamble.isEmpty() ? 0 : preamble.length();
            if (!currentFiles.isEmpty() && currentText.length() + prefixLen + chunk.length() > maxCharsPerBatch) {
                batches.add(finalize(preamble, currentText, currentFiles));
                preamble = "";
                currentText = new StringBuilder();
                currentFiles = new ArrayList<>();
            }
            currentText.append(chunk);
            currentFiles.add(file);
        }
        if (!currentFiles.isEmpty()) {
            batches.add(finalize(preamble, currentText, currentFiles));
        }
        return batches;
    }

    private static Batch finalize(String preamble, StringBuilder text, List<String> files) {
        String full = preamble + text;
        return new Batch(new DiffResult(full, false), List.copyOf(files));
    }

    /** チャンク先頭行が"diff --git a/... b/..."かどうかで新側(b/)パスを抽出する。マッチしなければ前置きとみなしnull。 */
    private static String extractFilePath(String chunk) {
        int newline = chunk.indexOf('\n');
        String firstLine = newline < 0 ? chunk : chunk.substring(0, newline);
        Matcher matcher = DIFF_GIT_LINE.matcher(firstLine);
        return matcher.matches() ? matcher.group(1) : null;
    }
}
