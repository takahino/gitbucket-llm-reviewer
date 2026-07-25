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
import io.github.takahino.llmreviewer.llm.LlmClient;
import io.github.takahino.llmreviewer.llm.LlmResponseParser;
import io.github.takahino.llmreviewer.llm.PromptBuilder;
import io.github.takahino.llmreviewer.llm.model.Finding;
import io.github.takahino.llmreviewer.llm.model.ReviewOutput;
import io.github.takahino.llmreviewer.rag.RagContextResolver;
import io.github.takahino.llmreviewer.rag.RagSearchResult;
import io.github.takahino.llmreviewer.scm.ScmClient;
import io.github.takahino.llmreviewer.scm.model.PullRequest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/** 1 PR に対するレビューパイプライン全体(diff取得 → 観点解決 → LLM Nパス → コメント投稿)を担う。 */
public class ReviewOrchestrator implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(ReviewOrchestrator.class.getName());
    private static final int MAX_FAILURES = 3;

    private final JGitDiffProvider jGitProvider;
    private final ApiDiffProvider apiFallbackProvider;
    private final LlmClient llmClient;
    private final ReviewStateStore stateStore;
    private final AppConfig.ReviewConfig reviewConfig;
    private final String llmModelName;
    private final CommentPublisher commentPublisher;
    private final RagContextResolver ragContextResolver;
    private final RepoReviewConfigFetcher repoReviewConfigFetcher;
    private final String scmBaseUrl;

    public ReviewOrchestrator(
            ScmClient scmClient,
            JGitDiffProvider jGitProvider,
            ApiDiffProvider apiFallbackProvider,
            LlmClient llmClient,
            ReviewStateStore stateStore,
            AppConfig.ReviewConfig reviewConfig,
            String llmModelName,
            boolean dryRun,
            RagContextResolver ragContextResolver,
            RepoReviewConfigFetcher repoReviewConfigFetcher,
            String scmBaseUrl
    ) {
        this.jGitProvider = jGitProvider;
        this.apiFallbackProvider = apiFallbackProvider;
        this.llmClient = llmClient;
        this.stateStore = stateStore;
        this.reviewConfig = reviewConfig;
        this.llmModelName = llmModelName;
        this.commentPublisher = new CommentPublisher(scmClient, dryRun);
        this.ragContextResolver = ragContextResolver;
        this.repoReviewConfigFetcher = repoReviewConfigFetcher;
        this.scmBaseUrl = scmBaseUrl;
    }

    /**
     * dry-run時のレビュー状態記録抑止は {@link ReviewStateStore} 自身が担う(CommentPublisherと同様、
     * dry-run可否はその副作用を持つ collaborator が自己判断する形に揃えている)。
     */
    public void reviewIfNeeded(AppConfig.RepositoryRef repoRef, PullRequest pr) {
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

    private void doReview(AppConfig.RepositoryRef repoRef, PullRequest pr) {
        String owner = repoRef.owner();
        String repoName = repoRef.name();
        String key = ReviewStateStore.key(owner, repoName, pr.number());

        RepoReviewConfigLoader.ParseResult parseResult = repoReviewConfigFetcher.fetchParsed(owner, repoName, pr.base().ref());
        if (!parseResult.warnings().isEmpty()) {
            LOGGER.warning(".review.yml に問題があります (%s): %s"
                    .formatted(key, String.join(" / ", parseResult.warnings())));
        }
        RepoReviewConfig repoConfig = parseResult.config();
        DiffOutcome diffOutcome = getDiffForReview(owner, repoName, pr, repoConfig.exclude(), stateStore.get(key));
        DiffResult diff = diffOutcome.diff();
        UnifiedDiffIndex diffIndex = UnifiedDiffIndex.parse(diff.diffText());
        List<RepoReviewConfig.PerspectiveGroup> perspectiveGroups = repoConfig.resolveGroupsFor(diffIndex.changedFiles());
        if (perspectiveGroups.isEmpty()) {
            LOGGER.info("適用可能な観点が0件のためレビューをスキップします(.review.yml未配置/パース失敗、または空観点): " + key);
            return;
        }
        List<String> fileTree = getFileTreeSafely(owner, repoName, pr.head().sha());
        Map<String, String> contextFiles = loadContextFiles(owner, repoName, pr.head().sha(), repoConfig.contextFiles());
        List<String> reviewContextPaths = perspectiveGroups.stream()
                .flatMap(g -> g.perspectives().stream())
                .flatMap(e -> e.resolvedContextPaths().stream())
                .distinct()
                .toList();
        Map<String, String> perspectiveContextFiles =
                loadContextFiles(owner, repoName, pr.head().sha(), reviewContextPaths);
        RagSearchResult ragResult =
                searchRagContextSafely(owner, repoName, pr, repoConfig, diff, diffIndex.changedFiles());
        Map<String, String> fullFileContext = collectFullFileContextIfEnabled(
                owner, repoName, pr.head().sha(), diffIndex.changedFiles(), fileTree);

        PromptBuilder promptBuilder = new PromptBuilder(reviewConfig.maxAdditionalFiles());
        ContextFileResolver contextFileResolver =
                new ContextFileResolver(jGitProvider, reviewConfig.maxAdditionalFiles(), reviewConfig.maxFileChars());
        FindingValidator findingValidator = new FindingValidator(jGitProvider);

        List<ChatMessage> conversation = new ArrayList<>();
        conversation.add(promptBuilder.systemMessage(repoConfig.language()));
        conversation.add(promptBuilder.initialUserMessage(
                pr, repoConfig, perspectiveGroups, fileTree, contextFiles, perspectiveContextFiles, ragResult, diff,
                diffOutcome.incrementalPreviousHeadSha(), fullFileContext));

        Set<String> referencedFiles = new LinkedHashSet<>();
        ReviewOutput output;
        int pass = 0;
        while (true) {
            pass++;
            output = chatAndParse(conversation, promptBuilder);
            if (output.needsMoreContext()) {
                if (pass >= reviewConfig.maxPasses()) {
                    conversation.add(promptBuilder.forceCompleteMessage());
                    output = chatAndParse(conversation, promptBuilder);
                    break;
                }
                Map<String, Optional<String>> resolved =
                        contextFileResolver.resolve(owner, repoName, pr.head().sha(), output.requestedFiles());
                referencedFiles.addAll(resolved.keySet());
                conversation.add(promptBuilder.additionalFilesMessage(resolved));
                continue;
            }

            List<FindingValidator.ValidationIssue> issues =
                    findingValidator.validate(owner, repoName, pr.head().sha(), output.findings());
            if (issues.isEmpty() || pass >= reviewConfig.maxPasses()) {
                break;
            }
            conversation.add(promptBuilder.findingsCorrectionMessage(issues));
        }

        // 最終防御: Nパスの矯正リトライで解消しきれなかった不正なfile/lineのfindingsを、投稿直前に除外する
        // (need_more_contextがmaxPassesに達してforceCompleteMessageで確定した場合は上のループ内で未検証のため)
        List<FindingValidator.ValidationIssue> finalIssues =
                findingValidator.validate(owner, repoName, pr.head().sha(), output.findings());
        if (!finalIssues.isEmpty()) {
            LOGGER.warning("findings検証で%d件の不正なfile/lineが見つかったため除外して投稿します: %s"
                    .formatted(finalIssues.size(), key));
            output = withoutInvalidFindings(output, finalIssues);
        }

        List<String> referencedFileList = List.copyOf(referencedFiles);
        String fileBlobBaseUrl = "%s/%s/%s/blob/%s".formatted(scmBaseUrl, owner, repoName, pr.head().sha());
        List<String> commentBodies = List.of(
                CommentFormatter.formatSummary(
                        output, pr.head().sha(), llmModelName, referencedFileList,
                        diffOutcome.incrementalPreviousHeadSha()),
                CommentFormatter.formatFindings(
                        output, pr.head().sha(), llmModelName, referencedFileList, repoConfig.maxComments(),
                        diffIndex, diffOutcome.incrementalPreviousHeadSha(), fileBlobBaseUrl)
        );
        commentPublisher.publish(owner, repoName, pr.number(), commentBodies);
    }

    /** maxPasses到達後もfile/lineが不正なfindingsが残っている場合の最終防御として、該当分を除外する。 */
    private static ReviewOutput withoutInvalidFindings(ReviewOutput output, List<FindingValidator.ValidationIssue> issues) {
        Set<Finding> invalid = issues.stream().map(FindingValidator.ValidationIssue::finding).collect(Collectors.toSet());
        List<Finding> filtered = output.findings().stream().filter(f -> !invalid.contains(f)).toList();
        return new ReviewOutput(output.status(), output.requestedFiles(), output.summary(), filtered);
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
            // AiMessageはnullのcontentを受け付けないため、content空/nullの応答(reasoning系モデルが
            // maxTokensを思考トークンで使い切った場合等)でも会話履歴には空文字として記録する。
            conversation.add(promptBuilder.assistantMessage(raw == null ? "" : raw));
            conversation.add(new UserMessage(
                    "直前の出力はJSONとして解析できませんでした。説明文やコードフェンスを含めず、"
                            + "指定されたJSONスキーマのオブジェクト1つのみを出力し直してください。"));
            String retryRaw = llmClient.chat(conversation);
            ReviewOutput output = LlmResponseParser.parse(retryRaw);
            conversation.add(promptBuilder.assistantMessage(retryRaw == null ? "" : retryRaw));
            return output;
        }
    }

    /** diffOutcome.incrementalPreviousHeadSha() が非nullなら増分レビュー、nullならPR全体レビュー。 */
    private record DiffOutcome(DiffResult diff, String incrementalPreviousHeadSha) {
    }

    /** 前回レビュー成功済みでheadShaが変化している場合は増分diffを試み、失敗・非該当時はPR全体のdiffにフォールバックする。 */
    private DiffOutcome getDiffForReview(
            String owner, String repoName, PullRequest pr, List<String> excludeGlobs,
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

    private DiffResult getDiffWithFallback(String owner, String repoName, PullRequest pr, List<String> excludeGlobs) {
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
            String owner, String repoName, PullRequest pr, RepoReviewConfig repoConfig,
            DiffResult diff, List<String> changedFiles) {
        try {
            return ragContextResolver.search(owner, repoName, pr, repoConfig, diff, changedFiles);
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "RAG検索に失敗したため、参考情報なしで継続します: %s/%s".formatted(owner, repoName), e);
            return RagSearchResult.empty();
        }
    }

    /** review.fullFileContextEnabled が有効な場合のみ、変更ファイル全文等の追加コンテキストを収集する(既定はOFF)。 */
    private Map<String, String> collectFullFileContextIfEnabled(
            String owner, String repoName, String headSha, List<String> changedFiles, List<String> fileTree) {
        if (!Boolean.TRUE.equals(reviewConfig.fullFileContextEnabled())) {
            return Map.of();
        }
        FullFileContextCollector collector =
                new FullFileContextCollector(jGitProvider, reviewConfig.fullFileContextMaxFiles(), reviewConfig.maxFileChars());
        return collector.collect(owner, repoName, headSha, changedFiles, fileTree);
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
            Optional<String> content = repoReviewConfigFetcher.fetchFile(owner, repoName, headSha, path);
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
