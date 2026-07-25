package io.github.takahino.llmreviewer.review;

import io.github.takahino.llmreviewer.git.RepositoryReader;
import io.github.takahino.llmreviewer.llm.model.Finding;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FindingValidatorTest {

    private static final String OWNER = "root";
    private static final String REPO = "sample";
    private static final String HEAD_SHA = "abc1234567";

    private static class FakeRepositoryReader implements RepositoryReader {
        private final Map<String, String> filesByPath;

        FakeRepositoryReader(Map<String, String> filesByPath) {
            this.filesByPath = filesByPath;
        }

        @Override
        public List<String> listFiles(String owner, String repo, String ref, int maxFiles) {
            return List.copyOf(filesByPath.keySet());
        }

        @Override
        public Optional<String> readFile(String owner, String repo, String ref, String path) {
            return Optional.ofNullable(filesByPath.get(path));
        }
    }

    @Test
    void validateReturnsNoIssueForExistingFileAndInRangeLine() {
        FindingValidator validator = new FindingValidator(
                new FakeRepositoryReader(Map.of("src/Foo.java", "line1\nline2\nline3")));
        Finding finding = new Finding("src/Foo.java", 2, "warning", "観点", "コメント");

        assertTrue(validator.validate(OWNER, REPO, HEAD_SHA, List.of(finding)).isEmpty());
    }

    @Test
    void validateAllowsNullLineWhenFileExists() {
        FindingValidator validator = new FindingValidator(
                new FakeRepositoryReader(Map.of("src/Foo.java", "line1\nline2")));
        Finding finding = new Finding("src/Foo.java", null, "info", "観点", "ファイル全体への言及");

        assertTrue(validator.validate(OWNER, REPO, HEAD_SHA, List.of(finding)).isEmpty());
    }

    @Test
    void validateFlagsNonexistentFile() {
        FindingValidator validator = new FindingValidator(new FakeRepositoryReader(Map.of()));
        Finding finding = new Finding("src/DoesNotExist.java", 1, "error", "観点", "コメント");

        List<FindingValidator.ValidationIssue> issues =
                validator.validate(OWNER, REPO, HEAD_SHA, List.of(finding));

        assertEquals(1, issues.size());
        assertTrue(issues.get(0).reason().contains("存在しません"));
    }

    @Test
    void validateFlagsLineNumberOutOfRange() {
        FindingValidator validator = new FindingValidator(
                new FakeRepositoryReader(Map.of("src/Foo.java", "line1\nline2")));
        Finding finding = new Finding("src/Foo.java", 999, "error", "観点", "コメント");

        List<FindingValidator.ValidationIssue> issues =
                validator.validate(OWNER, REPO, HEAD_SHA, List.of(finding));

        assertEquals(1, issues.size());
        assertTrue(issues.get(0).reason().contains("範囲外"));
    }

    @Test
    void validateAllowsFindingOutsideDiffAsLongAsItExistsInRepository() {
        // diffのhunk範囲かどうかは判定基準にせず、リポジトリ上に実在するかのみで判定する。
        FindingValidator validator = new FindingValidator(
                new FakeRepositoryReader(Map.of("src/Unrelated.java", "a\nb\nc\nd\ne")));
        Finding finding = new Finding("src/Unrelated.java", 5, "info", "観点", "diff外の周辺コードへの言及");

        assertTrue(validator.validate(OWNER, REPO, HEAD_SHA, List.of(finding)).isEmpty());
    }
}
