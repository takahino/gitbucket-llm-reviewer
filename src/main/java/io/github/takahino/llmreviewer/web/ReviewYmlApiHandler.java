package io.github.takahino.llmreviewer.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import io.github.takahino.llmreviewer.config.RepoReviewConfig;
import io.github.takahino.llmreviewer.gitbucket.GitBucketApiException;
import io.github.takahino.llmreviewer.gitbucket.GitBucketClient;
import io.github.takahino.llmreviewer.gitbucket.model.RepositoryDetail;
import io.github.takahino.llmreviewer.review.RepoReviewConfigFetcher;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * GET /api/review-yml?owner=&amp;repo= — config.ymlに登録済みのリポジトリについて、GitBucket上の実際の
 * .review.yml と、そこから参照される .review/ 配下のコンテキストファイル内容を読み取り専用で返す。
 */
final class ReviewYmlApiHandler implements HttpHandler {

    private final GitBucketClient gitBucketClient;
    private final RepoReviewConfigFetcher repoReviewConfigFetcher;

    ReviewYmlApiHandler(GitBucketClient gitBucketClient, RepoReviewConfigFetcher repoReviewConfigFetcher) {
        this.gitBucketClient = gitBucketClient;
        this.repoReviewConfigFetcher = repoReviewConfigFetcher;
    }

    record ContextFileView(String path, List<String> usedBy, String content, boolean found) {
    }

    record ReviewYmlView(boolean found, String raw, RepoReviewConfig parsed, List<ContextFileView> reviewContextFiles) {
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equals(exchange.getRequestMethod())) {
                JsonHttp.sendError(exchange, 405, "許可されていないメソッドです");
                return;
            }
            Map<String, String> query = JsonHttp.parseQuery(exchange.getRequestURI().getQuery());
            String owner = query.get("owner");
            String repo = query.get("repo");
            if (owner == null || owner.isBlank() || repo == null || repo.isBlank()) {
                JsonHttp.sendError(exchange, 400, "owner と repo は必須です");
                return;
            }
            handleReviewYml(exchange, owner, repo);
        } finally {
            exchange.close();
        }
    }

    private void handleReviewYml(HttpExchange exchange, String owner, String repo) throws IOException {
        String defaultBranch;
        try {
            RepositoryDetail detail = gitBucketClient.getRepository(owner, repo);
            defaultBranch = detail.defaultBranch();
        } catch (GitBucketApiException e) {
            JsonHttp.sendError(exchange, 502, "GitBucketからリポジトリ情報を取得できませんでした: " + e.getMessage());
            return;
        }

        Optional<String> raw = repoReviewConfigFetcher.fetchRaw(owner, repo, defaultBranch);
        RepoReviewConfig parsed = repoReviewConfigFetcher.parseRaw(raw);
        List<ContextFileView> contextFiles = resolveContextFiles(owner, repo, defaultBranch, parsed);

        JsonHttp.writeJson(exchange, 200, new ReviewYmlView(raw.isPresent(), raw.orElse(null), parsed, contextFiles));
    }

    /** perspectives(共通・path毎)が参照する .review/ 配下のファイルを収集し、実体を取得する。 */
    private List<ContextFileView> resolveContextFiles(
            String owner, String repo, String ref, RepoReviewConfig parsed) {
        Map<String, Set<String>> usedByPath = new LinkedHashMap<>();
        collectContextPaths(parsed.perspectives(), "共通", usedByPath);
        for (Map.Entry<String, RepoReviewConfig.PathConfig> entry : parsed.paths().entrySet()) {
            collectContextPaths(entry.getValue().perspectives(), entry.getKey(), usedByPath);
        }

        List<ContextFileView> result = new java.util.ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : usedByPath.entrySet()) {
            String path = entry.getKey();
            Optional<String> content = repoReviewConfigFetcher.fetchFile(owner, repo, ref, path);
            result.add(new ContextFileView(path, List.copyOf(entry.getValue()), content.orElse(null), content.isPresent()));
        }
        return result;
    }

    private void collectContextPaths(
            List<RepoReviewConfig.PerspectiveEntry> perspectives, String label, Map<String, Set<String>> usedByPath) {
        for (RepoReviewConfig.PerspectiveEntry entry : perspectives) {
            for (String path : entry.resolvedContextPaths()) {
                usedByPath.computeIfAbsent(path, k -> new LinkedHashSet<>()).add(label);
            }
        }
    }
}
