package io.github.takahino.llmreviewer.git;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnifiedDiffIndexTest {

    private static final String SAMPLE_DIFF = """
            diff --git a/src/Foo.java b/src/Foo.java
            index 111..222 100644
            --- a/src/Foo.java
            +++ b/src/Foo.java
            @@ -1,4 +1,5 @@
             line1
            -line2
            +line2modified
            +line2b
             line3
             line4
            diff --git a/old.txt b/new.txt
            similarity index 90%
            rename from old.txt
            rename to new.txt
            index 333..444 100644
            --- a/old.txt
            +++ b/new.txt
            @@ -10,3 +10,3 @@
             ctx1
            -removed
            +added
             ctx2
            """;

    @Test
    void changedFilesUsesNewSidePath() {
        UnifiedDiffIndex index = UnifiedDiffIndex.parse(SAMPLE_DIFF);
        assertEquals(List.of("src/Foo.java", "new.txt"), index.changedFiles());
    }

    @Test
    void snippetClipsToContextLinesWithinHunk() {
        UnifiedDiffIndex index = UnifiedDiffIndex.parse(SAMPLE_DIFF);
        Optional<String> snippet = index.snippet("src/Foo.java", 3, 1);
        assertTrue(snippet.isPresent());
        assertEquals("+line2modified\n+line2b\n line3", snippet.get());
    }

    @Test
    void snippetResolvesRenamedFileByNewPath() {
        UnifiedDiffIndex index = UnifiedDiffIndex.parse(SAMPLE_DIFF);
        assertTrue(index.snippet("old.txt", 11, 1).isEmpty());

        Optional<String> snippet = index.snippet("new.txt", 11, 1);
        assertTrue(snippet.isPresent());
        assertEquals("-removed\n+added\n ctx2", snippet.get());
    }

    @Test
    void snippetReturnsEmptyForUnknownFileOrLine() {
        UnifiedDiffIndex index = UnifiedDiffIndex.parse(SAMPLE_DIFF);
        assertTrue(index.snippet("does/not/exist.txt", 1, 3).isEmpty());
        assertTrue(index.snippet("src/Foo.java", 9999, 3).isEmpty());
        assertTrue(index.snippet("src/Foo.java", 0, 3).isEmpty());
    }
}
