package io.github.takahino.llmreviewer.git;

import io.github.takahino.llmreviewer.config.AppConfig;
import io.github.takahino.llmreviewer.gitbucket.model.PullRequestInfo;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** JGit によるプライマリの diff/ファイルアクセス実装。リポジトリ毎に {@link RepositoryMirror} をキャッシュする。 */
public class JGitDiffProvider implements DiffProvider, RepositoryReader, AutoCloseable {

    private final Path workDir;
    private final AppConfig.GitBucketConfig gitBucketConfig;
    private final Map<String, RepositoryMirror> mirrors = new ConcurrentHashMap<>();

    public JGitDiffProvider(Path workDir, AppConfig.GitBucketConfig gitBucketConfig) {
        this.workDir = workDir;
        this.gitBucketConfig = gitBucketConfig;
    }

    @Override
    public DiffResult getUnifiedDiff(String owner, String repo, PullRequestInfo pr, List<String> excludeGlobs, int maxChars) {
        RepositoryMirror mirror = mirrorFor(owner, repo);
        mirror.fetch();
        String mergeBase = mirror.resolveMergeBase(pr.base().sha(), pr.head().sha());
        String diff = mirror.unifiedDiff(mergeBase, pr.head().sha(), excludeGlobs);
        return truncate(diff, maxChars);
    }

    @Override
    public List<String> listFiles(String owner, String repo, String ref, int maxFiles) {
        return mirrorFor(owner, repo).listFiles(ref, maxFiles);
    }

    @Override
    public Optional<String> readFile(String owner, String repo, String ref, String path) {
        return mirrorFor(owner, repo).readFile(ref, path);
    }

    private RepositoryMirror mirrorFor(String owner, String repo) {
        return mirrors.computeIfAbsent(owner + "/" + repo,
                key -> new RepositoryMirror(workDir, owner, repo, gitBucketConfig));
    }

    private static DiffResult truncate(String diff, int maxChars) {
        if (diff.length() <= maxChars) {
            return new DiffResult(diff, false);
        }
        return new DiffResult(diff.substring(0, maxChars), true);
    }

    @Override
    public void close() {
        mirrors.values().forEach(RepositoryMirror::close);
    }
}
