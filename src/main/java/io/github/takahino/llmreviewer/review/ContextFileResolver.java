package io.github.takahino.llmreviewer.review;

import io.github.takahino.llmreviewer.git.RepositoryReader;
import io.github.takahino.llmreviewer.llm.model.FileRequest;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * LLMが要求した追加ファイルを解決する(全体整合チェックのNパスで使用)。
 * PRレビュー1回のセッション内で重複除去と maxAdditionalFiles/maxFileChars の上限制御を行う。
 */
public class ContextFileResolver {

    private static final Logger LOGGER = Logger.getLogger(ContextFileResolver.class.getName());

    private final RepositoryReader repositoryReader;
    private final int maxAdditionalFiles;
    private final int maxFileChars;
    private final Set<String> providedPaths = new LinkedHashSet<>();

    public ContextFileResolver(RepositoryReader repositoryReader, int maxAdditionalFiles, int maxFileChars) {
        this.repositoryReader = repositoryReader;
        this.maxAdditionalFiles = maxAdditionalFiles;
        this.maxFileChars = maxFileChars;
    }

    public Map<String, Optional<String>> resolve(String owner, String repo, String ref, List<FileRequest> requests) {
        Map<String, Optional<String>> result = new LinkedHashMap<>();
        for (FileRequest request : requests) {
            if (providedPaths.size() >= maxAdditionalFiles) {
                break;
            }
            String path = request.path();
            if (path == null || path.isBlank() || providedPaths.contains(path)) {
                continue;
            }
            providedPaths.add(path);
            result.put(path, readSafely(owner, repo, ref, path));
        }
        return result;
    }

    private Optional<String> readSafely(String owner, String repo, String ref, String path) {
        try {
            return repositoryReader.readFile(owner, repo, ref, path).map(this::truncate);
        } catch (RuntimeException e) {
            LOGGER.log(Level.FINE, "追加ファイルの取得に失敗しました: " + path, e);
            return Optional.empty();
        }
    }

    private String truncate(String content) {
        if (content.length() <= maxFileChars) {
            return content;
        }
        return content.substring(0, maxFileChars) + "\n...(以降は文字数上限のため切り詰め)";
    }
}
