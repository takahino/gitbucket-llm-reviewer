package io.github.takahino.llmreviewer.git;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiffBatcherTest {

    private static String fileDiff(String path, int addedLines) {
        StringBuilder sb = new StringBuilder();
        sb.append("diff --git a/").append(path).append(" b/").append(path).append('\n');
        sb.append("index 111..222 100644\n--- a/").append(path).append("\n+++ b/").append(path).append('\n');
        sb.append("@@ -1,1 +1,").append(addedLines + 1).append(" @@\n");
        sb.append(" line1\n");
        for (int i = 0; i < addedLines; i++) {
            sb.append("+line").append(i).append('\n');
        }
        return sb.toString();
    }

    @Test
    void smallDiffFitsInSingleBatch() {
        String diff = fileDiff("src/A.java", 2) + fileDiff("src/B.java", 2);

        DiffBatcher.Result result = DiffBatcher.split(diff, 100_000, 20);

        assertEquals(1, result.batches().size());
        assertTrue(result.skippedFiles().isEmpty());
        assertEquals(List.of("src/A.java", "src/B.java"), result.batches().get(0).changedFiles());
        assertFalse(result.batches().get(0).diff().truncated());
    }

    @Test
    void largeDiffSplitsWithoutBreakingFileBoundaries() {
        String chunkA = fileDiff("src/A.java", 2);
        String chunkB = fileDiff("src/B.java", 2);
        String chunkC = fileDiff("src/C.java", 2);
        // A+Bがちょうど収まり、Cは次バッチに押し出される上限にする
        int maxCharsPerBatch = chunkA.length() + chunkB.length();
        String diff = chunkA + chunkB + chunkC;

        DiffBatcher.Result result = DiffBatcher.split(diff, maxCharsPerBatch, 20);

        assertEquals(2, result.batches().size());
        assertTrue(result.skippedFiles().isEmpty());
        assertEquals(List.of("src/A.java", "src/B.java"), result.batches().get(0).changedFiles());
        assertEquals(List.of("src/C.java"), result.batches().get(1).changedFiles());
        // 各バッチのdiffテキストがファイル境界で完全な塊のままであること
        assertEquals(chunkA + chunkB, result.batches().get(0).diff().diffText());
        assertEquals(chunkC, result.batches().get(1).diff().diffText());
    }

    @Test
    void batchCountExceedingMaxBatchesReportsSkippedFiles() {
        String chunkA = fileDiff("src/A.java", 1);
        String chunkB = fileDiff("src/B.java", 1);
        String chunkC = fileDiff("src/C.java", 1);
        String chunkD = fileDiff("src/D.java", 1);
        // 1バッチ1ファイルになるよう上限を各チャンク長ちょうどにする
        int maxCharsPerBatch = Math.max(Math.max(chunkA.length(), chunkB.length()),
                Math.max(chunkC.length(), chunkD.length()));
        String diff = chunkA + chunkB + chunkC + chunkD;

        DiffBatcher.Result result = DiffBatcher.split(diff, maxCharsPerBatch, 2);

        assertEquals(2, result.batches().size());
        assertEquals(List.of("src/A.java"), result.batches().get(0).changedFiles());
        assertEquals(List.of("src/B.java"), result.batches().get(1).changedFiles());
        assertEquals(List.of("src/C.java", "src/D.java"), result.skippedFiles());
    }

    @Test
    void singleFileExceedingLimitBecomesTruncatedSoloBatch() {
        String hugeChunk = fileDiff("src/Huge.java", 100);
        String smallChunk = fileDiff("src/Small.java", 1);
        int maxCharsPerBatch = hugeChunk.length() / 2;
        String diff = hugeChunk + smallChunk;

        DiffBatcher.Result result = DiffBatcher.split(diff, maxCharsPerBatch, 20);

        assertEquals(2, result.batches().size());
        DiffBatcher.Batch first = result.batches().get(0);
        assertEquals(List.of("src/Huge.java"), first.changedFiles());
        assertTrue(first.diff().truncated());
        assertEquals(maxCharsPerBatch, first.diff().diffText().length());

        DiffBatcher.Batch second = result.batches().get(1);
        assertEquals(List.of("src/Small.java"), second.changedFiles());
        assertFalse(second.diff().truncated());
    }

    @Test
    void preambleIsAttachedToFirstBatchAndNotCountedAsFile() {
        String preamble = "# 注意: このdiffはコミット単位パッチの連結によるフォールバック生成です。\n\n";
        String chunkA = fileDiff("src/A.java", 1);
        String diff = preamble + chunkA;

        DiffBatcher.Result result = DiffBatcher.split(diff, 100_000, 20);

        assertEquals(1, result.batches().size());
        assertEquals(List.of("src/A.java"), result.batches().get(0).changedFiles());
        assertTrue(result.batches().get(0).diff().diffText().startsWith(preamble));
    }

    @Test
    void emptyDiffProducesNoBatches() {
        DiffBatcher.Result result = DiffBatcher.split("", 1000, 20);

        assertTrue(result.batches().isEmpty());
        assertTrue(result.skippedFiles().isEmpty());
    }
}
