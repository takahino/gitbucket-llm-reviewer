package io.github.takahino.llmreviewer.review;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import io.github.takahino.llmreviewer.config.AppConfig;
import io.github.takahino.llmreviewer.config.RepoReviewConfig;
import io.github.takahino.llmreviewer.config.RepoReviewConfigLoader;
import io.github.takahino.llmreviewer.git.ApiDiffProvider;
import io.github.takahino.llmreviewer.git.DiffResult;
import io.github.takahino.llmreviewer.git.GitMirrorException;
import io.github.takahino.llmreviewer.git.JGitDiffProvider;
import io.github.takahino.llmreviewer.git.UnifiedDiffIndex;
import io.github.takahino.llmreviewer.gitbucket.GitBucketApiException;
import io.github.takahino.llmreviewer.gitbucket.GitBucketClient;
import io.github.takahino.llmreviewer.gitbucket.model.PullRequestInfo;
import io.github.takahino.llmreviewer.llm.LlmClient;
import io.github.takahino.llmreviewer.llm.LlmResponseParser;
import io.github.takahino.llmreviewer.llm.PromptBuilder;
import io.github.takahino.llmreviewer.llm.model.ReviewOutput;
import io.github.takahino.llmreviewer.rag.RagContextResolver;
import io.github.takahino.llmreviewer.rag.RagSearchResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/** 1 PR に対するレビューパイプライン全体(diff取得 → 観点解決 → LLM Nパス → コメント投稿)を担う。 */
public class ReviewOrchestrator implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(ReviewOrchestrator.class.getName());
    private static final int MAX_FAILURES = 3;

    private final GitBucketClient gitBucketClient;
    private final JGitDiffProvider jGitProvider;
    private final ApiDiffProvider apiFallbackProvider;
    private final LlmClient llmClient;
    private final ReviewStateStore stateStore;
    private final AppConfig.ReviewConfig reviewConfig;
    private final String llmModelName;
    private final CommentPublisher commentPublisher;
    private final RagContextResolver ragContextResolver;

    public ReviewOrchestrator(
            GitBucketClient gitBucketClient,
            JGitDiffProvider jGitProvider,
            ApiDiffProvider apiFallbackProvider,
            LlmClient llmClient,
            ReviewStateStore stateStore,
            AppConfig.ReviewConfig reviewConfig,
            String llmModelName,
            boolean dryRun,
            RagContextResolver ragContextResolver
    ) {
        this.gitBucketClient = gitBucketClient;
        this.jGitProvider = jGitProvider;
        this.apiFallbackProvider = apiFallbackProvider;
        this.llmClient = llmClient;
        this.stateStore = stateStore;
        this.reviewConfig = reviewConfig;
        this.llmModelName = llmModelName;
        this.commentPublisher = new CommentPublisher(gitBucketClient, dryRun);
        this.ragContextResolver = ragContextResolver;
    }

    public void reviewIfNeeded(AppConfig.RepositoryRef repoRef, PullRequestInfo pr) {
        String key = ReviewStateStore.key(repoRef.owner(), repoRef.name(), pr.number());
        if (!stateStore.needsReview(key, pr.head().sha())) {
            return;
        }
        LOGGER.info("レビュー対象PRを検出しました: %s (head=%s)".formatted(key, pr.head().sha()));
        try {
            doReview(repoRef, pr);
            stateStore.markReviewed(key, pr.head().sha());
            LOGGER.info("レビューを完了しました: " + key);
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "PRレビューに失敗しました: " + key, e);
            stateStore.markFailed(key, pr.head().sha(), MAX_FAILURES);
        }
    }

    private void doReview(AppConfig.RepositoryRef repoRef, PullRequestInfo pr) {
        String owner = repoRef.owner();
        String repoName = repoRef.name();
        String key = ReviewStateStore.key(owner, repoName, pr.number());

        RepoReviewConfig repoConfig = loadRepoReviewConfig(owner, repoName, pr.base().ref());
        DiffOutcome diffOutcome = getDiffForReview(owner, repoName, pr, repoConfig.exclude(), stateStore.get(key));
        DiffResult diff = diffOutcome.diff();
        UnifiedDiffIndex diffIndex = UnifiedDiffIndex.parse(diff.diffText());
        List<RepoReviewConfig.PerspectiveGroup> perspectiveGroups = repoConfig.resolveGroupsFor(diffIndex.changedFiles());
        List<String> fileTree = getFileTreeSafely(owner, repoName, pr.head().sha());
        Map<String, String> contextFiles = loadContextFiles(owner, repoName, pr.head().sha(), repoConfig.contextFiles());
        RagSearchResult ragResult =
                searchRagContextSafely(owner, repoName, pr, repoConfig, diff, diffIndex.changedFiles());

        PromptBuilder promptBuilder = new PromptBuilder(reviewConfig.maxAdditionalFiles());
        ContextFileResolver contextFileResolver =
                new ContextFileResolver(jGitProvider, reviewConfig.maxAdditionalFiles(), reviewConfig.maxFileChars());

        List<ChatMessage> conversation = new ArrayList<>();
        conversation.add(promptBuilder.systemMessage());
        conversation.add(promptBuilder.initialUserMessage(
                pr, repoConfig, perspectiveGroups, fileTree, contextFiles, ragResult, diff,
                diffOutcome.incrementalPreviousHeadSha()));

        Set<String> referencedFiles = new LinkedHashSet<>();
        ReviewOutput output;
        int pass = 0;
        while (true) {
            pass++;
            output = chatAndParse(conversation, promptBuilder);
            if (!output.needsMoreContext()) {
                break;
            }
            if (pass >= reviewConfig.maxPasses()) {
                conversation.add(promptBuilder.forceCompleteMessage());
                output = chatAndParse(conversation, promptBuilder);
                break;
            }
            Map<String, Optional<String>> resolved =
                    contextFileResolver.resolve(owner, repoName, pr.head().sha(), output.requestedFiles());
            referencedFiles.addAll(resolved.keySet());
            conversation.add(promptBuilder.additionalFilesMessage(resolved));
        }

        List<String> referencedFileList = List.copyOf(referencedFiles);
        List<String> commentBodies = List.of(
                CommentFormatter.formatSummary(
                        output, pr.head().sha(), llmModelName, referencedFileList,
                        diffOutcome.incrementalPreviousHeadSha()),
                CommentFormatter.formatFindings(
                        output, pr.head().sha(), llmModelName, referencedFileList, repoConfig.maxComments(),
                        diffIndex, diffOutcome.incrementalPreviousHeadSha())
        );
        commentPublisher.publish(owner, repoName, pr.number(), commentBodies);
    }

    /** LLMに問い合わせ、JSONパースに失敗した場合は1回だけ矯正リトライする。成功時は会話履歴にassistant応答を積む。 */
    private ReviewOutput chatAndParse(List<ChatMessage> conversation, PromptBuilder promptBuilder) {
        String raw = llmClient.chat(conversation);
        try {
            ReviewOutput output = LlmResponseParser.parse(raw);
            conversation.add(promptBuilder.assistantMessage(raw));
            return output;
        } catch (RuntimeException parseError) {
            LOGGER.log(Level.WARNING, "LLM応答のJSONパースに失敗しました。矯正リトライを行います", parseError);
            conversation.add(promptBuilder.assistantMessage(raw));
            conversation.add(new UserMessage(
                    "直前の出力はJSONとして解析できませんでした。説明文やコードフェンスを含めず、"
                            + "指定されたJSONスキーマのオブジェクト1つのみを出力し直してください。"));
            String retryRaw = llmClient.chat(conversation);
            ReviewOutput output = LlmResponseParser.parse(retryRaw);
            conversation.add(promptBuilder.assistantMessage(retryRaw));
            return output;
        }
    }

    private RepoReviewConfig loadRepoReviewConfig(String owner, String repoName, String baseRef) {
        Optional<String> content;
        try {
            content = gitBucketClient.getRawContent(owner, repoName, ".review.yml", baseRef);
        } catch (GitBucketApiException e) {
            LOGGER.log(Level.WARNING,
                    ".review.yml のAPI取得に失敗、JGitでの読み込みにフォールバックします: %s/%s".formatted(owner, repoName), e);
            content = readReviewYmlViaJGit(owner, repoName, baseRef);
        }
        return RepoReviewConfigLoader.parse(content.orElse(null));
    }

    private Optional<String> readReviewYmlViaJGit(String owner, String repoName, String baseRef) {
        try {
            return jGitProvider.readFile(owner, repoName, baseRef, ".review.yml");
        } catch (GitMirrorException e) {
            LOGGER.log(Level.WARNING,
                    ".review.yml のJGit読み込みにも失敗、デフォルト観点を使用します: %s/%s".formatted(owner, repoName), e);
            return Optional.empty();
        }
    }

    /** diffOutcome.incrementalPreviousHeadSha() が非nullなら増分レビュー、nullならPR全体レビュー。 */
    private record DiffOutcome(DiffResult diff, String incrementalPreviousHeadSha) {
    }

    /** 前回レビュー成功済みでheadShaが変化している場合は増分diffを試み、失敗・非該当時はPR全体のdiffにフォールバックする。 */
    private DiffOutcome getDiffForReview(
            String owner, String repoName, PullRequestInfo pr, List<String> excludeGlobs,
            Optional<ReviewStateStore.StateEntry> previousState
    ) {
        if (previousState.isPresent()) {
            ReviewStateStore.StateEntry prev = previousState.get();
            if ("reviewed".equals(prev.status()) && !prev.reviewedHeadSha().equals(pr.head().sha())) {
                try {
                    DiffResult incrementalDiff = jGitProvider.getIncrementalDiff(
                            owner, repoName, prev.reviewedHeadSha(), pr.head().sha(), excludeGlobs, reviewConfig.maxDiffChars());
                    return new DiffOutcome(incrementalDiff, prev.reviewedHeadSha());
                } catch (GitMirrorException e) {
                    LOGGER.log(Level.WARNING,
                            "増分diffの取得に失敗したため全量diffにフォールバックします: %s/%s".formatted(owner, repoName), e);
                }
            }
        }
        return new DiffOutcome(getDiffWithFallback(owner, repoName, pr, excludeGlobs), null);
    }

    private DiffResult getDiffWithFallback(String owner, String repoName, PullRequestInfo pr, List<String> excludeGlobs) {
        try {
            return jGitProvider.getUnifiedDiff(owner, repoName, pr, excludeGlobs, reviewConfig.maxDiffChars());
        } catch (GitMirrorException e) {
            LOGGER.log(Level.WARNING,
                    "JGitによるdiff取得に失敗したためAPIフォールバックを使用します: %s/%s".formatted(owner, repoName), e);
            return apiFallbackProvider.getUnifiedDiff(owner, repoName, pr, excludeGlobs, reviewConfig.maxDiffChars());
        }
    }

    /** RAG検索の失敗(embeddingサーバー不通等)がレビュー全体を止めないよう、失敗時は空結果にフォールバックする。 */
    private RagSearchResult searchRagContextSafely(
            String owner, String repoName, PullRequestInfo pr, RepoReviewConfig repoConfig,
            DiffResult diff, List<String> changedFiles) {
        try {
            return ragContextResolver.search(owner, repoName, pr, repoConfig, diff, changedFiles);
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "RAG検索に失敗したため、参考情報なしで継続します: %s/%s".formatted(owner, repoName), e);
            return RagSearchResult.empty();
        }
    }

    private List<String> getFileTreeSafely(String owner, String repoName, String headSha) {
        try {
            return jGitProvider.listFiles(owner, repoName, headSha, 500);
        } catch (GitMirrorException e) {
            LOGGER.log(Level.FINE, "ファイル一覧取得に失敗したため省略します: %s/%s".formatted(owner, repoName), e);
            return List.of();
        }
    }

    private Map<String, String> loadContextFiles(String owner, String repoName, String headSha, List<String> contextFilePaths) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String path : contextFilePaths) {
            Optional<String> content = Optional.empty();
            try {
                content = jGitProvider.readFile(owner, repoName, headSha, path);
            } catch (GitMirrorException ignored) {
                // API フォールバックへ
            }
            if (content.isEmpty()) {
                try {
                    content = gitBucketClient.getRawContent(owner, repoName, path, headSha);
                } catch (GitBucketApiException ignored) {
                    content = Optional.empty();
                }
            }
            if (content.isPresent()) {
                result.put(path, content.get());
            } else {
                LOGGER.warning("contextFiles の取得に失敗したためスキップします: " + path);
            }
        }
        return result;
    }

    /** グレースフル停止時にJGitのローカルミラー(Repositoryハンドル)を解放する。 */
    @Override
    public void close() {
        jGitProvider.close();
    }
}
