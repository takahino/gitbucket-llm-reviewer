package io.github.takahino.llmreviewer.review;

import io.github.takahino.llmreviewer.config.AppConfig;
import io.github.takahino.llmreviewer.scm.ScmClient;
import io.github.takahino.llmreviewer.scm.model.PullRequest;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/** 監視対象リポジトリの open PR を定期的に走査し、レビューが必要なPRをオーケストレーターに渡す。 */
public class PollingService {

    private static final Logger LOGGER = Logger.getLogger(PollingService.class.getName());

    private final ScmClient scmClient;
    private final ReviewOrchestrator orchestrator;
    private final MentionReplyOrchestrator mentionReplyOrchestrator;
    private final List<AppConfig.RepositoryRef> repositories;
    private final int intervalSeconds;

    public PollingService(
            ScmClient scmClient,
            ReviewOrchestrator orchestrator,
            MentionReplyOrchestrator mentionReplyOrchestrator,
            List<AppConfig.RepositoryRef> repositories,
            int intervalSeconds
    ) {
        this.scmClient = scmClient;
        this.orchestrator = orchestrator;
        this.mentionReplyOrchestrator = mentionReplyOrchestrator;
        this.repositories = repositories;
        this.intervalSeconds = intervalSeconds;
    }

    /** 1回だけ全リポジトリを走査する(--once用)。PR単位・リポジトリ単位の例外は隔離しループを継続する。 */
    public void runOnce() {
        for (AppConfig.RepositoryRef repo : repositories) {
            try {
                List<PullRequest> openPrs = scmClient.listOpenPullRequests(repo.owner(), repo.name());
                for (PullRequest pr : openPrs) {
                    try {
                        orchestrator.reviewIfNeeded(repo, pr);
                    } catch (RuntimeException e) {
                        LOGGER.log(Level.SEVERE,
                                "PR単位の処理で予期しないエラーが発生しました: %s#%d".formatted(repo.fullName(), pr.number()), e);
                    }
                    // メンション応答は自動レビューとは独立した経路のため、片方の失敗がもう片方をブロックしないよう
                    // 別のtry/catchで処理する。
                    try {
                        mentionReplyOrchestrator.respondToMentionsIfAny(repo, pr);
                    } catch (RuntimeException e) {
                        LOGGER.log(Level.SEVERE,
                                "メンション応答処理で予期しないエラーが発生しました: %s#%d".formatted(repo.fullName(), pr.number()), e);
                    }
                }
            } catch (RuntimeException e) {
                LOGGER.log(Level.SEVERE, "リポジトリの PR 一覧取得に失敗しました: " + repo.fullName(), e);
            }
        }
    }

    /**
     * intervalSeconds 毎に runOnce を実行し続ける(常駐モード)。次回実行までの残り秒数をコンソールに
     * 毎秒上書き表示する(ScheduledExecutorServiceでは次回発火までの残り時間を外から把握しづらいため、
     * 明示的なループ+Thread.sleepで管理している)。
     */
    public void startPolling() {
        Thread pollingThread = new Thread(this::pollingLoop, "polling-service");
        pollingThread.setDaemon(false);
        pollingThread.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOGGER.info("シャットダウン要求を受信、ポーリングを停止します");
            pollingThread.interrupt();
            try {
                pollingThread.join(30_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            orchestrator.close();
            LOGGER.info("ポーリングを停止しました");
        }, "polling-shutdown-hook"));
    }

    private void pollingLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            runOnce();
            if (!countdownBeforeNextRun()) {
                return;
            }
        }
    }

    /** intervalSeconds秒のカウントダウンを標準出力に毎秒上書き表示する。割り込まれたらfalseを返す。 */
    private boolean countdownBeforeNextRun() {
        for (int remaining = intervalSeconds; remaining > 0; remaining--) {
            System.out.print("\r次回ポーリングまで: %3d秒 ".formatted(remaining));
            System.out.flush();
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.out.println();
                return false;
            }
        }
        // カウントダウン表示を消してから次のrunOnceのログ出力に移る
        System.out.print("\r" + " ".repeat(20) + "\r");
        System.out.flush();
        return true;
    }
}
