package io.github.takahino.llmreviewer.git;

import io.github.takahino.llmreviewer.scm.GitRemoteLocator;
import io.github.takahino.llmreviewer.scm.model.PullRequest;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** JGit によるプライマリの diff/ファイルアクセス実装。リポジトリ毎に {@link RepositoryMirror} をキャッシュする。 */
public class JGitDiffProvider implements DiffProvider, RepositoryReader, AutoCloseable {

    private final Path workDir;
    private final GitRemoteLocator remoteLocator;
    private final Map<String, RepositoryMirror> mirrors = new ConcurrentHashMap<>();

    public JGitDiffProvider(Path workDir, GitRemoteLocator remoteLocator) {
        this.workDir = workDir;
        this.remoteLocator = remoteLocator;
    }

    @Override
    public String getUnifiedDiff(String owner, String repo, PullRequest pr, List<String> excludeGlobs) {
        RepositoryMirror mirror = mirrorFor(owner, repo);
        mirror.fetch();
        String mergeBase = mirror.resolveMergeBase(pr.base().sha(), pr.head().sha());
        return mirror.unifiedDiff(mergeBase, pr.head().sha(), excludeGlobs);
    }

    /** 前回レビュー済みheadShaから現在のheadShaまでの増分diffを取得する(増分レビュー用)。 */
    public String getIncrementalDiff(
            String owner, String repo, String previousHeadSha, String currentHeadSha, List<String> excludeGlobs) {
        RepositoryMirror mirror = mirrorFor(owner, repo);
        mirror.fetch();
        return mirror.unifiedDiff(previousHeadSha, currentHeadSha, excludeGlobs);
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
                key -> new RepositoryMirror(workDir, owner, repo, remoteLocator));
    }

    @Override
    public void close() {
        mirrors.values().forEach(RepositoryMirror::close);
    }
}
