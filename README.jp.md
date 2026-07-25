# gitbucket-llm-reviewer

[GitBucket](https://github.com/gitbucket/gitbucket) のプルリクエストをポーリングで監視し、OpenAI互換API(Ollama / LM Studio / vLLM 等)経由でローカル/セルフホストのLLMによる自動コードレビューを行う、Java 21製の独立アプリケーションです。CodeRabbitのような自動レビューを、自前のインフラだけで実現します。

> English version: [README.md](README.md)

## 主な機能

- **ポーリング監視** — 監視対象リポジトリの open なプルリクエストを定期的に走査し、新規PR・既存PRへのpush更新を検出します(レビュー済み状態を永続化するため二重レビューは発生しません)。
- **変更内容の要約** — PRの変更内容のサマリを生成します。
- **リポジトリ毎のレビュー観点** — リポジトリルートの `.review.yml` で定義した観点でチェックします。
- **モノレポ対応** — `.review.yml` の `paths` でフォルダ(glob)毎に観点を追加できます(例: `frontend/**` と `backend/**` で異なる観点)。
- **全体整合チェック(Nパス)** — diffだけでは判断がつかない場合(呼び出し元だけ変更され呼び出し先の実装が見えていない等)、LLMが追加ファイルを要求できます。ツールがそのファイルを取得して再問い合わせすることを、設定した回数まで繰り返します。
- **疑似インラインコメント** — GitBucketにはPRのdiff行へ直接コメントするAPIが無いため、指摘箇所周辺のコードをコメント内に引用することで、インラインコメントに近い体験を提供します。
- **増分レビュー** — 前回レビュー成功時のheadShaを記録しており、pushで新しいコミットが追加された場合はPR全体ではなく差分(前回レビュー以降のコミット)のみを対象にレビューします(取得できない場合はPR全体のレビューにフォールバックします)。
- **LLM呼び出しのリトライ** — LLMサーバーへの接続断・タイムアウト・5xxエラーは指数バックオフで自動リトライします(4xxエラーはリトライしません)。
- **レビューコメントの折りたたみ** — pushの度に新規コメントが積み上がって読みにくくならないよう、投稿前にbot自身の過去のレビューコメントを `<details>` で折りたたみます。
- **UTF-8前提にしない** — 実運用のコードベースは必ずしもUTF-8とは限らない(Shift_JIS、EUC-JP等)ため、ソースファイルの文字コードを自動判定してデコードします。
- **堅牢なdiff取得** — GitBucketのREST APIには `pulls/:id/files` やcompare相当のエンドポイントが無いため、GitBucketのgit smart HTTPに対するJGitでのmerge-base差分取得をプライマリとし、失敗時はREST APIのコミット単位パッチを連結するフォールバックに切り替えます。
- **PRへのコメント投稿** — レビュー結果をPRコメントとして投稿し、あわせて構造化ログにも出力します。

## 動作要件

- Java 21以上
- Maven 3.9以上
- GitBucketの稼働環境(4.46.1で動作確認済み)とAPIトークン
- OpenAI互換のLLMエンドポイント(`ollama serve`、LM Studio、vLLM等)

## ビルド

```bash
mvn package
```

`target/gitbucket-llm-reviewer.jar` に実行可能なfat-jarが生成されます。

## 設定

`config.example.yml` を `config.yml` としてコピーし(`.gitignore` 済み。実トークンは絶対にコミットしないこと)、値を設定してください。

```yaml
gitbucket:
  baseUrl: http://localhost:8080
  token: "xxxxxxxx"
  gitUsername: ""     # JGit fetch用(任意)。空ならAPIトークンをBasic認証のusername/passwordとして試行
  gitPassword: ""
repositories:
  - owner: root
    name: sample-repo
polling:
  intervalSeconds: 60
llm:
  baseUrl: http://localhost:11434/v1
  model: qwen2.5-coder:14b
  apiKey: ""          # 一部のOpenAI互換サーバーでのみ必要
  temperature: 0.2
  maxTokens: 4096
  timeoutSeconds: 300
  retryMaxAttempts: 3   # LLM呼び出し失敗時の最大試行回数(初回含む)
  retryBackoffMs: 2000  # リトライ毎の待機時間(ミリ秒、指数的に増加)
review:
  maxDiffChars: 60000
  maxAdditionalFiles: 5
  maxFileChars: 50000
  maxPasses: 3
  foldPreviousComments: true  # 過去のレビューコメントを折りたたんでから新規コメントを投稿する
state:
  filePath: ./data/review-state.json
workDir: ./data/repos
```

### リポジトリ側 `.review.yml`

レビュー対象の各リポジトリのルートに `.review.yml` を配置してください(`review.example.yml` をコピー)。存在しない、または解析できない場合はデフォルトの観点で継続します。

```yaml
language: ja
perspectives:                 # リポジトリ全体に適用する観点
  - "セキュリティ上の懸念(インジェクション、認可漏れ)"
  - "既存コードとの命名・設計の一貫性"
paths:                        # モノレポ対応: フォルダ(glob)毎の追加観点
  "frontend/**":
    perspectives:
      - "React hooksの依存配列漏れ"
      - "XSS(dangerouslySetInnerHTML等)"
    inherit: true             # 上記の共通観点も適用する(デフォルトtrue)
  "backend/**":
    perspectives:
      - "トランザクション境界の妥当性"
      - "N+1クエリ"
exclude:                      # diffから除外するglob
  - "**/*.min.js"
contextFiles:                 # 常にコンテキストとして渡すファイル(任意)
  - "README.md"
maxComments: 10
```

## 使い方

```bash
java -jar target/gitbucket-llm-reviewer.jar --config config.yml
```

オプション:

| オプション | 説明 |
|---|---|
| `--config <path>` | `config.yml` のパス(デフォルト: `./config.yml`) |
| `--once` | 常駐せず1回だけ走査して終了する |
| `--dry-run` | 生成したレビューコメントをGitBucketに投稿せず、ログに出力するだけにする |

`--once` を指定しない場合、プロセスは常駐し `polling.intervalSeconds` 毎に走査を繰り返します。SIGTERMを受けると、実行中の走査が完了してからJGitのリポジトリハンドルを解放して停止します(グレースフルシャットダウン)。

## 仕組み

1. `PollingService` が監視対象リポジトリ毎に open なPRを取得し、まだレビューしていないhead commitを持つPRを `ReviewOrchestrator` に渡します。
2. `ReviewOrchestrator` が `.review.yml` を読み込み(GitBucketのcontents API、失敗時はJGit経由)、diffを計算し(JGitによるmerge-base差分をプライマリ、失敗時はコミット単位パッチの連結にフォールバック)、適用すべき観点(共通+モノレポのパスグループ)を解決し、任意のコンテキストファイルを収集します。
3. LLMにdiff・観点・リポジトリのファイル一覧を渡します。`need_more_context` が返された場合は要求されたファイルを取得(重複除去・サイズ上限あり)し、`review.maxPasses` 回まで再問い合わせします。
4. 最終的なサマリと指摘事項をMarkdownに整形し、PRコメントとして投稿します。レビュー済みのhead SHAを永続化するため、同じコミットが二重にレビューされることはありません。

## 既知の制約

- GitBucketのREST APIには `pulls/:id/files` やcompare相当のエンドポイントが無いため、プライマリのdiff取得はJGitによるgit smart HTTP経由のfetchに依存します。認証がうまくいかない場合は `config.yml` の `gitUsername`/`gitPassword` を明示的に設定してください。
- APIベースのdiffフォールバックはコミット単位パッチの連結による近似であり、厳密なmerge-base差分ではありません。
- GitBucketのREST APIにはPRのdiff行への直接コメント(GitHubのPull Request Review Comments相当)が無いため、指摘箇所の周辺コードをIssueコメント内に引用する「疑似インライン化」で代替しています。真のdiff行コメントではありません。
- 増分レビューは、前回レビュー済みheadShaのGitオブジェクトがローカルミラーから取得できる場合のみ有効です。force-pushやミラーのgc等で取得できない場合はPR全体のレビューにフォールバックします。
- レビューコメントの折りたたみ(`review.foldPreviousComments`)は、GitBucketのIssueコメント一覧取得(GET)・編集(PATCH)APIがGitHub v3互換のパス(`/repos/:owner/:repo/issues/:number/comments`、`/repos/:owner/:repo/issues/comments/:id`)で提供されている前提です。トークンに十分な権限が無い場合は折りたたみをスキップし、新規コメント投稿は継続します。

## ライセンス

[Apache License, Version 2.0](LICENSE) のもとで公開しています。
