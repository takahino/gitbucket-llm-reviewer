package io.github.takahino.llmreviewer.review;

import io.github.takahino.llmreviewer.config.RepoReviewConfigLoader;
import io.github.takahino.llmreviewer.git.GitMirrorException;
import io.github.takahino.llmreviewer.git.RepositoryReader;
import io.github.takahino.llmreviewer.scm.ScmApiException;
import io.github.takahino.llmreviewer.scm.ScmClient;

import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * リポジトリ側の任意ファイル(.review.yml、.review/配下のコンテキストファイル等)を
 * REST API優先・JGitフォールバックで取得する共通ロジック。
 * ReviewOrchestrator(PRレビュー時)と管理UI(review.yml表示)の両方から利用する。
 */
public class RepoReviewConfigFetcher {

    private static final Logger LOGGER = Logger.getLogger(RepoReviewConfigFetcher.class.getName());
    private static final String REVIEW_YML_PATH = ".review.yml";

    private final ScmClient scmClient;
    private final RepositoryReader repositoryReader;

    public RepoReviewConfigFetcher(ScmClient scmClient, RepositoryReader repositoryReader) {
        this.scmClient = scmClient;
        this.repositoryReader = repositoryReader;
    }

    /**
     * .review.yml の生の中身を取得する。REST APIが404(未配置)を返した場合はJGitへフォールバックしない
     * (存在しないことがAPIで確定しているため)。REST APIそのものが失敗した場合のみJGitで読み直す。
     */
    public Optional<String> fetchRaw(String owner, String repoName, String ref) {
        Optional<String> content;
        try {
            content = scmClient.getRawContent(owner, repoName, REVIEW_YML_PATH, ref);
        } catch (ScmApiException e) {
            LOGGER.log(Level.WARNING,
                    ".review.yml のAPI取得に失敗、JGitでの読み込みにフォールバックします: %s/%s".formatted(owner, repoName), e);
            content = readViaJGit(owner, repoName, REVIEW_YML_PATH, ref);
        }
        return content;
    }

    public RepoReviewConfigLoader.ParseResult fetchParsed(String owner, String repoName, String ref) {
        return parseRaw(fetchRaw(owner, repoName, ref));
    }

    /** 既に取得済みの生YAML文字列をパースする(呼び出し側がrawとparsedの両方を必要とする場合の再フェッチ防止用)。 */
    public RepoReviewConfigLoader.ParseResult parseRaw(Optional<String> raw) {
        return RepoReviewConfigLoader.parse(raw.orElse(null));
    }

    /**
     * 任意ファイルをJGit優先・REST APIフォールバックで取得する(.review/配下のコンテキストファイル等)。
     * JGit読み込みが空(未取得・ファイル不在いずれも含む)であれば、REST APIでの再取得を試みる。
     */
    public Optional<String> fetchFile(String owner, String repoName, String ref, String path) {
        Optional<String> content = Optional.empty();
        try {
            content = repositoryReader.readFile(owner, repoName, ref, path);
        } catch (GitMirrorException ignored) {
            // API フォールバックへ
        }
        if (content.isEmpty()) {
            try {
                content = scmClient.getRawContent(owner, repoName, path, ref);
            } catch (ScmApiException ignored) {
                content = Optional.empty();
            }
        }
        return content;
    }

    private Optional<String> readViaJGit(String owner, String repoName, String path, String ref) {
        try {
            return repositoryReader.readFile(owner, repoName, ref, path);
        } catch (GitMirrorException e) {
            LOGGER.log(Level.WARNING,
                    "%s のJGit読み込みにも失敗、デフォルト観点を使用します: %s/%s".formatted(path, owner, repoName), e);
            return Optional.empty();
        }
    }
}
