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
import io.github.takahino.llmreviewer.gitbucket.GitBucketClient;
import io.github.takahino.llmreviewer.gitbucket.model.IssueComment;
import io.github.takahino.llmreviewer.gitbucket.model.PullRequestInfo;
import io.github.takahino.llmreviewer.llm.LlmClient;
import io.github.takahino.llmreviewer.llm.LlmResponseParser;
import io.github.takahino.llmreviewer.llm.MentionReplyPromptBuilder;
import io.github.takahino.llmreviewer.llm.model.MentionReplyOutput;
import io.github.takahino.llmreviewer.rag.RagContextResolver;
import io.github.takahino.llmreviewer.rag.RagSearchResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * PRコメントでのBotメンション(追質問/追レビュー)を検知し、LLMで応答してコメント投稿するパイプライン。
 * {@link ReviewOrchestrator}(自動レビュー)とは独立して動作し、.review.yml の有無に関わらず常時稼働する
 * (perspectiveGroupsが空でも打ち切らず、メンションコメントの文面を観点代わりに使う)。
 */
public class MentionReplyOrchestrator {

    private static final Logger LOGGER = Logger.getLogger(MentionReplyOrchestrator.class.getName());
    private static final int MAX_HISTORY_COMMENTS = 30;

    private final GitBucketClient gitBucketClient;
    private final JGitDiffProvider jGitProvider;
    private final ApiDiffProvider apiFallbackProvider;
    private final LlmClient llmClient;
    private final MentionStateStore mentionStateStore;
    private final AppConfig.ReviewConfig reviewConfig;
    private final String llmModelName;
    private final CommentPublisher commentPublisher;
    private final RagContextResolver ragContextResolver;
    private final RepoReviewConfigFetcher repoReviewConfigFetcher;
    private final String botUsername;

    /** botUsername が null の場合、respondToMentionsIfAny は常に即returnし機能全体を無効化する(Bot名解決失敗時)。 */
    public MentionReplyOrchestrator(
            GitBucketClient gitBucketClient,
            JGitDiffProvider jGitProvider,
            ApiDiffProvider apiFallbackProvider,
            LlmClient llmClient,
            MentionStateStore mentionStateStore,
            AppConfig.ReviewConfig reviewConfig,
            String llmModelName,
            boolean dryRun,
            RagContextResolver ragContextResolver,
            RepoReviewConfigFetcher repoReviewConfigFetcher,
            String botUsername
    ) {
        this.gitBucketClient = gitBucketClient;
        this.jGitProvider = jGitProvider;
        this.apiFallbackProvider = apiFallbackProvider;
        this.llmClient = llmClient;
        this.mentionStateStore = mentionStateStore;
        this.reviewConfig = reviewConfig;
        this.llmModelName = llmModelName;
        this.commentPublisher = new CommentPublisher(gitBucketClient, dryRun);
        this.ragContextResolver = ragContextResolver;
        this.repoReviewConfigFetcher = repoReviewConfigFetcher;
        this.botUsername = botUsername;
    }

    public void respondToMentionsIfAny(AppConfig.RepositoryRef repoRef, PullRequestInfo pr) {
        if (botUsername == null) {
            return;
        }
        String owner = repoRef.owner();
        String repoName = repoRef.name();
        String key = MentionStateStore.key(owner, repoName, pr.number());

        List<IssueComment> comments;
        try {
            comments = new ArrayList<>(gitBucketClient.listIssueComments(owner, repoName, pr.number()));
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "コメント一覧の取得に失敗しました: " + key, e);
            return;
        }
        comments.sort(Comparator.comparingLong(IssueComment::id));

        Optional<Long> lastProcessed = mentionStateStore.get(key);
        if (lastProcessed.isEmpty()) {
            // 初観測: デプロイ直後/新規PRで過去メンションに一括応答しないよう、基準点の記録のみ行う
            markUpToLatest(key, comments);
            return;
        }

        long lastProcessedId = lastProcessed.get();
        List<IssueComment> newMentions = comments.stream()
                .filter(c -> c.id() > lastProcessedId)
                .filter(c -> !isFromBot(c))
                .filter(c -> MentionDetector.mentions(c.body(), botUsername))
                .toList();

        if (newMentions.isEmpty()) {
            markUpToLatest(key, comments);
            return;
        }

        for (IssueComment trigger : newMentions) {
            try {
                respondToOne(owner, repoName, pr, comments, trigger);
                mentionStateStore.markProcessed(key, trigger.id());
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING,
                        "メンション応答に失敗しました(次回ポーリングで再試行します): %s (commentId=%d)"
                                .formatted(key, trigger.id()), e);
                break;
            }
        }
    }

    private void markUpToLatest(String key, List<IssueComment> comments) {
        comments.stream().mapToLong(IssueComment::id).max()
                .ifPresent(maxId -> mentionStateStore.markProcessed(key, maxId));
    }

    private boolean isFromBot(IssueComment comment) {
        return comment.user() != null && botUsername.equals(comment.user().login());
    }

    private void respondToOne(
            String owner, String repoName, PullRequestInfo pr, List<IssueComment> comments, IssueComment trigger
    ) {
        String key = MentionStateStore.key(owner, repoName, pr.number());
        RepoReviewConfigLoader.ParseResult parseResult =
                repoReviewConfigFetcher.fetchParsed(owner, repoName, pr.base().ref());
        if (!parseResult.warnings().isEmpty()) {
            LOGGER.warning(".review.yml に問題があります (%s): %s"
                    .formatted(key, String.join(" / ", parseResult.warnings())));
        }
        RepoReviewConfig repoConfig = parseResult.config();

        DiffResult diff = getDiffWithFallback(owner, repoName, pr, repoConfig.exclude());
        UnifiedDiffIndex diffIndex = UnifiedDiffIndex.parse(diff.diffText());
        List<RepoReviewConfig.PerspectiveGroup> perspectiveGroups =
                repoConfig.resolveGroupsFor(diffIndex.changedFiles());

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

        List<IssueComment> history = comments.stream()
                .filter(c -> c.id() < trigger.id())
                .sorted(Comparator.comparingLong(IssueComment::id))
                .toList();
        if (history.size() > MAX_HISTORY_COMMENTS) {
            history = history.subList(history.size() - MAX_HISTORY_COMMENTS, history.size());
        }

        MentionReplyPromptBuilder promptBuilder = new MentionReplyPromptBuilder(reviewConfig.maxAdditionalFiles());
        ContextFileResolver contextFileResolver =
                new ContextFileResolver(jGitProvider, reviewConfig.maxAdditionalFiles(), reviewConfig.maxFileChars());

        List<ChatMessage> conversation = new ArrayList<>();
        conversation.add(promptBuilder.systemMessage());
        conversation.add(promptBuilder.initialUserMessage(
                pr, perspectiveGroups, fileTree, contextFiles, perspectiveContextFiles, ragResult, diff,
                history, trigger, botUsername));

        Set<String> referencedFiles = new LinkedHashSet<>();
        MentionReplyOutput output;
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

        String triggerUserLogin = trigger.user() != null ? trigger.user().login() : null;
        String replyBody = CommentFormatter.formatMentionReply(
                output, pr.head().sha(), llmModelName, List.copyOf(referencedFiles), trigger.id(), triggerUserLogin);
        commentPublisher.publish(owner, repoName, pr.number(), List.of(replyBody));
    }

    /** LLMに問い合わせ、JSONパースに失敗した場合は1回だけ矯正リトライする(ReviewOrchestrator.chatAndParseと同じ方針)。 */
    private MentionReplyOutput chatAndParse(List<ChatMessage> conversation, MentionReplyPromptBuilder promptBuilder) {
        String raw = llmClient.chat(conversation);
        try {
            MentionReplyOutput output = LlmResponseParser.parse(raw, MentionReplyOutput.class);
            conversation.add(promptBuilder.assistantMessage(raw));
            return output;
        } catch (RuntimeException parseError) {
            LOGGER.log(Level.WARNING, "LLM応答のJSONパースに失敗しました。矯正リトライを行います", parseError);
            conversation.add(promptBuilder.assistantMessage(raw));
            conversation.add(new UserMessage(
                    "直前の出力はJSONとして解析できませんでした。説明文やコードフェンスを含めず、"
                            + "指定されたJSONスキーマのオブジェクト1つのみを出力し直してください。"));
            String retryRaw = llmClient.chat(conversation);
            MentionReplyOutput output = LlmResponseParser.parse(retryRaw, MentionReplyOutput.class);
            conversation.add(promptBuilder.assistantMessage(retryRaw));
            return output;
        }
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
            Optional<String> content = repoReviewConfigFetcher.fetchFile(owner, repoName, headSha, path);
            if (content.isPresent()) {
                result.put(path, content.get());
            } else {
                LOGGER.warning("contextFiles の取得に失敗したためスキップします: " + path);
            }
        }
        return result;
    }
}
