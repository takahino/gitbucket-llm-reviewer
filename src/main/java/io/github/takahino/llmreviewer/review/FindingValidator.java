package io.github.takahino.llmreviewer.review;

import io.github.takahino.llmreviewer.git.RepositoryReader;
import io.github.takahino.llmreviewer.llm.model.Finding;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * LLMが返したfindingsのfile/lineが実際にリポジトリ上に存在するかを検証する(ハルシネーション対策)。
 * diffの変更行内かどうかは問わない: レビュー観点に基づく周辺コードへの正当な言及を誤って弾かないよう、
 * 「ファイルが存在するか」「行番号がそのファイルの行数の範囲内か」のみを判定基準とする。
 */
public class FindingValidator {

    private static final Logger LOGGER = Logger.getLogger(FindingValidator.class.getName());

    private final RepositoryReader repositoryReader;

    public FindingValidator(RepositoryReader repositoryReader) {
        this.repositoryReader = repositoryReader;
    }

    public record ValidationIssue(Finding finding, String reason) {
    }

    public List<ValidationIssue> validate(String owner, String repo, String headSha, List<Finding> findings) {
        List<ValidationIssue> issues = new ArrayList<>();
        Map<String, Optional<Integer>> lineCountCache = new LinkedHashMap<>();
        for (Finding finding : findings) {
            String file = finding.file();
            if (file == null || file.isBlank()) {
                issues.add(new ValidationIssue(finding, "file が指定されていません"));
                continue;
            }
            Optional<Integer> lineCount =
                    lineCountCache.computeIfAbsent(file, f -> countLines(owner, repo, headSha, f));
            if (lineCount.isEmpty()) {
                issues.add(new ValidationIssue(finding, "ファイル \"%s\" はリポジトリ上に存在しません".formatted(file)));
                continue;
            }
            Integer line = finding.line();
            if (line != null && (line < 1 || line > lineCount.get())) {
                issues.add(new ValidationIssue(finding,
                        "行番号 %d は \"%s\" の行数(%d行)の範囲外です".formatted(line, file, lineCount.get())));
            }
        }
        return issues;
    }

    private Optional<Integer> countLines(String owner, String repo, String headSha, String file) {
        try {
            return repositoryReader.readFile(owner, repo, headSha, file)
                    .map(content -> (int) content.lines().count());
        } catch (RuntimeException e) {
            LOGGER.log(Level.FINE, "findings検証のためのファイル取得に失敗しました: " + file, e);
            return Optional.empty();
        }
    }
}
