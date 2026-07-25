package io.github.takahino.llmreviewer.gitbucket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.takahino.llmreviewer.config.AppConfig;
import io.github.takahino.llmreviewer.gitbucket.model.CommitDetail;
import io.github.takahino.llmreviewer.gitbucket.model.CommitRef;
import io.github.takahino.llmreviewer.gitbucket.model.GitUser;
import io.github.takahino.llmreviewer.gitbucket.model.IssueComment;
import io.github.takahino.llmreviewer.gitbucket.model.PullRequestInfo;
import io.github.takahino.llmreviewer.gitbucket.model.RepositoryDetail;
import io.github.takahino.llmreviewer.util.CharsetDetector;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/** GitBucket の GitHub 互換 REST API (v3) を呼び出す薄いクライアント。 */
public class GitBucketClient {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final ObjectMapper jsonMapper = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .build()
            .findAndRegisterModules();

    private final HttpClient httpClient;
    private final String baseUrl;
    private final String token;

    public GitBucketClient(AppConfig.GitBucketConfig config) {
        this.baseUrl = config.baseUrl();
        this.token = config.token();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public List<PullRequestInfo> listOpenPullRequests(String owner, String repo) {
        URI uri = apiUri("/repos/%s/%s/pulls".formatted(owner, repo), Map.of("state", "open"));
        String body = sendJsonRequest(newRequestBuilder(uri).GET());
        return parseList(body, PullRequestInfo.class);
    }

    public PullRequestInfo getPullRequest(String owner, String repo, int number) {
        URI uri = apiUri("/repos/%s/%s/pulls/%d".formatted(owner, repo, number), Map.of());
        String body = sendJsonRequest(newRequestBuilder(uri).GET());
        return parse(body, PullRequestInfo.class);
    }

    public List<CommitRef> listPullRequestCommits(String owner, String repo, int number) {
        URI uri = apiUri("/repos/%s/%s/pulls/%d/commits".formatted(owner, repo, number), Map.of());
        String body = sendJsonRequest(newRequestBuilder(uri).GET());
        return parseList(body, CommitRef.class);
    }

    public CommitDetail getCommitDetail(String owner, String repo, String sha) {
        URI uri = apiUri("/repos/%s/%s/commits/%s".formatted(owner, repo, sha), Map.of());
        String body = sendJsonRequest(newRequestBuilder(uri).GET());
        return parse(body, CommitDetail.class);
    }

    /** トークンに紐づく認証済みユーザー情報を取得する。メンション応答機能でBot自身のユーザー名を解決するために使用する。 */
    public GitUser getAuthenticatedUser() {
        URI uri = apiUri("/user", Map.of());
        String body = sendJsonRequest(newRequestBuilder(uri).GET());
        return parse(body, GitUser.class);
    }

    /** リポジトリ情報(デフォルトブランチ等)を取得する。PRに紐づかない文脈(管理UI等)でのブランチ解決に使う。 */
    public RepositoryDetail getRepository(String owner, String repo) {
        URI uri = apiUri("/repos/%s/%s".formatted(owner, repo), Map.of());
        String body = sendJsonRequest(newRequestBuilder(uri).GET());
        return parse(body, RepositoryDetail.class);
    }

    /** 指定 ref のファイルの生の中身を取得する。存在しない場合は空を返す。 */
    public Optional<String> getRawContent(String owner, String repo, String path, String ref) {
        URI uri = apiUri("/repos/%s/%s/contents/%s".formatted(owner, repo, encodePath(path)), Map.of("ref", ref));
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "token " + token)
                .header("Accept", "application/vnd.github.v3.raw")
                .GET()
                .build();
        HttpResponse<byte[]> response = sendBytes(request);
        if (response.statusCode() == 404) {
            return Optional.empty();
        }
        requireSuccess(response);
        // レビュー対象コードはUTF-8とは限らない(Shift_JIS等)ため、生バイトのまま文字コードを自動判定する
        return Optional.of(CharsetDetector.decode(response.body()));
    }

    public void postIssueComment(String owner, String repo, int issueNumber, String commentBody) {
        URI uri = apiUri("/repos/%s/%s/issues/%d/comments".formatted(owner, repo, issueNumber), Map.of());
        String requestJson = commentBodyJson(commentBody);
        HttpRequest request = newRequestBuilder(uri)
                .POST(HttpRequest.BodyPublishers.ofString(requestJson, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = send(request);
        requireSuccess(response);
    }

    /** PRの過去コメントを折りたたむため、Issueコメント一覧を取得する。 */
    public List<IssueComment> listIssueComments(String owner, String repo, int issueNumber) {
        URI uri = apiUri("/repos/%s/%s/issues/%d/comments".formatted(owner, repo, issueNumber), Map.of());
        String body = sendJsonRequest(newRequestBuilder(uri).GET());
        return parseList(body, IssueComment.class);
    }

    /** 既存のIssueコメント本文を更新する(過去のレビューコメントを折りたたむために使用)。 */
    public void updateIssueComment(String owner, String repo, long commentId, String newBody) {
        URI uri = apiUri("/repos/%s/%s/issues/comments/%d".formatted(owner, repo, commentId), Map.of());
        String requestJson = commentBodyJson(newBody);
        HttpRequest request = newRequestBuilder(uri)
                .method("PATCH", HttpRequest.BodyPublishers.ofString(requestJson, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = send(request);
        requireSuccess(response);
    }

    private String commentBodyJson(String commentBody) {
        try {
            return jsonMapper.writeValueAsString(Map.of("body", commentBody));
        } catch (IOException e) {
            throw new GitBucketApiException("コメント本文のJSON化に失敗しました", e);
        }
    }

    private HttpRequest.Builder newRequestBuilder(URI uri) {
        return HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "token " + token)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json; charset=utf-8");
    }

    private String sendJsonRequest(HttpRequest.Builder builder) {
        HttpResponse<String> response = send(builder.build());
        requireSuccess(response);
        return response.body();
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new GitBucketApiException("GitBucket への通信に失敗しました: " + request.uri(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GitBucketApiException("GitBucket への通信が割り込まれました: " + request.uri(), e);
        }
    }

    private HttpResponse<byte[]> sendBytes(HttpRequest request) {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new GitBucketApiException("GitBucket への通信に失敗しました: " + request.uri(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GitBucketApiException("GitBucket への通信が割り込まれました: " + request.uri(), e);
        }
    }

    private void requireSuccess(HttpResponse<?> response) {
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw new GitBucketApiException("GitBucket API がエラーを返しました(status=%d, url=%s): %s"
                    .formatted(status, response.request().uri(), bodySnippet(response.body())), status);
        }
    }

    private static String bodySnippet(Object body) {
        String text = switch (body) {
            case String s -> s;
            case byte[] b -> new String(b, StandardCharsets.UTF_8);
            case null -> "";
            default -> String.valueOf(body);
        };
        return text.substring(0, Math.min(500, text.length()));
    }

    private <T> T parse(String body, Class<T> type) {
        try {
            return jsonMapper.readValue(body, type);
        } catch (IOException e) {
            throw new GitBucketApiException("GitBucket API 応答のパースに失敗しました(type=%s)".formatted(type.getSimpleName()), e);
        }
    }

    private <T> List<T> parseList(String body, Class<T> elementType) {
        try {
            var listType = jsonMapper.getTypeFactory().constructCollectionType(List.class, elementType);
            return jsonMapper.readValue(body, listType);
        } catch (IOException e) {
            throw new GitBucketApiException("GitBucket API 応答(配列)のパースに失敗しました(type=%s)".formatted(elementType.getSimpleName()), e);
        }
    }

    private URI apiUri(String path, Map<String, String> queryParams) {
        String query = queryParams.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        String fullPath = baseUrl + "/api/v3" + path;
        return URI.create(query.isEmpty() ? fullPath : fullPath + "?" + query);
    }

    private String encodePath(String path) {
        return Arrays.stream(path.split("/"))
                .map(segment -> URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20"))
                .collect(Collectors.joining("/"));
    }
}
