# gitbucket-llm-reviewer

[GitBucket](https://github.com/gitbucket/gitbucket) のプルリクエストをポーリングで監視し、OpenAI互換API(Ollama / LM Studio / vLLM 等)経由でローカル/セルフホストのLLMによる自動コードレビューを行う、Java 21製の独立アプリケーションです。CodeRabbitのような自動レビューを、自前のインフラだけで実現します。

> English version: [README.md](README.md)

## 主な機能

- **ポーリング監視** — 監視対象リポジトリの open なプルリクエストを定期的に走査し、新規PR・既存PRへのpush更新を検出します(レビュー済み状態を永続化するため二重レビューは発生しません)。
- **変更内容の要約** — PRの変更内容のサマリを生成します。
- **リポジトリ毎のレビュー観点** — リポジトリルートの `.review.yml` で定義した観点でチェックします。
- **モノレポ対応** — `.review.yml` の `paths` でフォルダ(glob)毎に観点を追加できます(例: `frontend/**` と `backend/**` で異なる観点)。
- **全体整合チェック(Nパス)** — diffだけでは判断がつかない場合(呼び出し元だけ変更され呼び出し先の実装が見えていない等)、LLMが追加ファイルを要求できます。ツールがそのファイルを取得して再問い合わせすることを、設定した回数まで繰り返します。
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
review:
  maxDiffChars: 60000
  maxAdditionalFiles: 5
  maxFileChars: 50000
  maxPasses: 3
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

## ライセンス

[Apache License, Version 2.0](LICENSE) のもとで公開しています。
