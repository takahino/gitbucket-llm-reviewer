package io.github.takahino.llmreviewer.git;

import io.github.takahino.llmreviewer.gitbucket.GitBucketClient;
import io.github.takahino.llmreviewer.gitbucket.model.CommitDetail;
import io.github.takahino.llmreviewer.gitbucket.model.CommitFileEntry;
import io.github.takahino.llmreviewer.gitbucket.model.CommitRef;
import io.github.takahino.llmreviewer.gitbucket.model.PullRequestInfo;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JGit による merge-base 差分取得が失敗した場合のフォールバック。
 * pulls/:id/commits と commits/:sha の patch を連結するため、
 * 複数コミットにまたがる累積差分としては近似(厳密なmerge-base差分ではない)。
 */
public class ApiDiffProvider implements DiffProvider {

    private final GitBucketClient client;

    public ApiDiffProvider(GitBucketClient client) {
        this.client = client;
    }

    @Override
    public DiffResult getUnifiedDiff(String owner, String repo, PullRequestInfo pr, List<String> excludeGlobs, int maxChars) {
        List<PathMatcher> matchers = excludeGlobs.stream()
                .map(glob -> FileSystems.getDefault().getPathMatcher("glob:" + glob))
                .toList();

        // 同一ファイルに複数コミットのパッチがある場合は最後のものを残す(累積ではなく最終差分への近似)
        Map<String, String> latestPatchByFile = new LinkedHashMap<>();
        List<CommitRef> commits = client.listPullRequestCommits(owner, repo, pr.number());
        for (CommitRef commitRef : commits) {
            CommitDetail detail = client.getCommitDetail(owner, repo, commitRef.sha());
            for (CommitFileEntry file : detail.files()) {
                if (file.patch() == null) {
                    continue;
                }
                if (matchers.stream().anyMatch(m -> m.matches(Path.of(file.filename())))) {
                    continue;
                }
                latestPatchByFile.put(file.filename(), formatPatch(file));
            }
        }

        StringBuilder combined = new StringBuilder();
        combined.append("# 注意: このdiffはコミット単位パッチの連結によるフォールバック生成です。\n")
                .append("# merge-base差分ではないため、複数コミットにまたがる変更の累積が正確でない場合があります。\n\n");
        latestPatchByFile.values().forEach(combined::append);

        String diff = combined.toString();
        if (diff.length() <= maxChars) {
            return new DiffResult(diff, false);
        }
        return new DiffResult(diff.substring(0, maxChars), true);
    }

    private static String formatPatch(CommitFileEntry file) {
        return "diff --git a/%s b/%s\n%s\n".formatted(file.filename(), file.filename(), file.patch());
    }
}
