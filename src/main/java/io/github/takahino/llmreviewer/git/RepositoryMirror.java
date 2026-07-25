package io.github.takahino.llmreviewer.git;

import io.github.takahino.llmreviewer.config.AppConfig;
import io.github.takahino.llmreviewer.util.CharsetDetector;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.filter.RevFilter;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.TreeWalk;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** GitBucket 上の1リポジトリに対応するローカル bare ミラー。fetch・diff・ファイル読み込みを担う。 */
public class RepositoryMirror implements AutoCloseable {

    private final Path gitDir;
    private final String remoteUrl;
    private final CredentialsProvider credentialsProvider;
    private Repository repository;

    public RepositoryMirror(Path workDir, String owner, String repoName, AppConfig.GitBucketConfig gitBucketConfig) {
        this.gitDir = workDir.resolve(owner).resolve(repoName + ".git");
        this.remoteUrl = gitBucketConfig.baseUrl() + "/git/" + owner + "/" + repoName + ".git";
        this.credentialsProvider = resolveCredentials(gitBucketConfig);
    }

    private static CredentialsProvider resolveCredentials(AppConfig.GitBucketConfig config) {
        // gitUsername/gitPassword が明示設定されていればそれを優先。
        // 未設定ならAPIトークンをBasic認証のusername/passwordとして試みる。
        if (!config.gitUsername().isBlank()) {
            return new UsernamePasswordCredentialsProvider(config.gitUsername(), config.gitPassword());
        }
        return new UsernamePasswordCredentialsProvider(config.token(), config.token());
    }

    public synchronized void fetch() {
        try {
            Repository repo = open();
            List<RefSpec> refSpecs = List.of(
                    new RefSpec("+refs/heads/*:refs/heads/*"),
                    new RefSpec("+refs/pull/*:refs/pull/*")
            );
            try (Git git = new Git(repo)) {
                git.fetch()
                        .setRemote(remoteUrl)
                        .setRefSpecs(refSpecs)
                        .setCredentialsProvider(credentialsProvider)
                        .setTimeout(30)
                        .call();
            }
        } catch (GitAPIException | IOException e) {
            throw new GitMirrorException(
                    "git fetch に失敗しました(%s)。認証エラーの場合は config.yml の gitUsername/gitPassword を設定してください: %s"
                            .formatted(remoteUrl, e.getMessage()), e);
        }
    }

    private Repository open() throws IOException {
        if (repository != null) {
            return repository;
        }
        boolean exists = gitDir.resolve("HEAD").toFile().isFile();
        Files.createDirectories(gitDir);
        Repository repo = new FileRepositoryBuilder().setGitDir(gitDir.toFile()).setBare().build();
        if (!exists) {
            repo.create(true);
        }
        this.repository = repo;
        return repo;
    }

    public String resolveMergeBase(String baseSha, String headSha) {
        try (RevWalk walk = new RevWalk(repository)) {
            walk.setRevFilter(RevFilter.MERGE_BASE);
            RevCommit baseCommit = walk.parseCommit(repository.resolve(baseSha));
            RevCommit headCommit = walk.parseCommit(repository.resolve(headSha));
            walk.markStart(baseCommit);
            walk.markStart(headCommit);
            RevCommit mergeBase = walk.next();
            return mergeBase != null ? mergeBase.getName() : baseSha;
        } catch (IOException e) {
            throw new GitMirrorException("merge-base の解決に失敗しました(base=%s, head=%s)".formatted(baseSha, headSha), e);
        }
    }

    public String unifiedDiff(String oldRef, String newRef, List<String> excludeGlobs) {
        try {
            ObjectId oldTreeId = repository.resolve(oldRef + "^{tree}");
            ObjectId newTreeId = repository.resolve(newRef + "^{tree}");
            if (oldTreeId == null || newTreeId == null) {
                throw new GitMirrorException("diff対象のtreeが解決できません(old=%s, new=%s)".formatted(oldRef, newRef));
            }
            List<PathMatcher> matchers = excludeGlobs.stream()
                    .map(glob -> FileSystems.getDefault().getPathMatcher("glob:" + glob))
                    .toList();

            try (ObjectReader reader = repository.newObjectReader();
                 Git git = new Git(repository)) {
                CanonicalTreeParser oldTree = new CanonicalTreeParser();
                oldTree.reset(reader, oldTreeId);
                CanonicalTreeParser newTree = new CanonicalTreeParser();
                newTree.reset(reader, newTreeId);

                List<DiffEntry> entries = git.diff().setOldTree(oldTree).setNewTree(newTree).call();

                ByteArrayOutputStream out = new ByteArrayOutputStream();
                try (DiffFormatter formatter = new DiffFormatter(out)) {
                    formatter.setRepository(repository);
                    for (DiffEntry entry : entries) {
                        String path = "/dev/null".equals(entry.getNewPath()) ? entry.getOldPath() : entry.getNewPath();
                        if (matchers.stream().anyMatch(m -> m.matches(Path.of(path)))) {
                            continue;
                        }
                        formatter.format(entry);
                    }
                }
                // レビュー対象コードはUTF-8とは限らない(Shift_JIS等)ため文字コードを自動判定する
                return CharsetDetector.decode(out.toByteArray());
            }
        } catch (IOException | GitAPIException e) {
            throw new GitMirrorException("diff生成に失敗しました(old=%s, new=%s)".formatted(oldRef, newRef), e);
        }
    }

    /**
     * fetch()未実行(ミラー未作成)の状態でも呼ばれ得る(例: 管理UIからのreview.yml表示はPRのdiff取得を経由しない)。
     * その場合はopen()でミラーを開くのみでリモートの内容は取得しない(treeIdが解決できずOptional.empty()を返す)。
     */
    public Optional<String> readFile(String ref, String path) {
        try {
            Repository repo = open();
            ObjectId treeId = repo.resolve(ref + "^{tree}");
            if (treeId == null) {
                return Optional.empty();
            }
            try (TreeWalk treeWalk = TreeWalk.forPath(repo, path, treeId)) {
                if (treeWalk == null) {
                    return Optional.empty();
                }
                ObjectId blobId = treeWalk.getObjectId(0);
                try (ObjectReader reader = repo.newObjectReader()) {
                    // レビュー対象コードはUTF-8とは限らない(Shift_JIS等)ため文字コードを自動判定する
                    return Optional.of(CharsetDetector.decode(reader.open(blobId).getBytes()));
                }
            }
        } catch (IOException e) {
            throw new GitMirrorException("ファイル読み込みに失敗しました(ref=%s, path=%s)".formatted(ref, path), e);
        }
    }

    /** readFile と同様、fetch()未実行でも呼ばれ得るためopen()でミラーを開く。 */
    public List<String> listFiles(String ref, int maxFiles) {
        try {
            Repository repo = open();
            ObjectId treeId = repo.resolve(ref + "^{tree}");
            if (treeId == null) {
                return List.of();
            }
            List<String> paths = new ArrayList<>();
            try (TreeWalk treeWalk = new TreeWalk(repo)) {
                treeWalk.addTree(treeId);
                treeWalk.setRecursive(true);
                while (treeWalk.next() && paths.size() < maxFiles) {
                    paths.add(treeWalk.getPathString());
                }
            }
            return paths;
        } catch (IOException e) {
            throw new GitMirrorException("ファイル一覧取得に失敗しました(ref=%s)".formatted(ref), e);
        }
    }

    @Override
    public void close() {
        if (repository != null) {
            repository.close();
        }
    }
}
