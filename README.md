# gitbucket-llm-reviewer

A standalone Java 21 application that polls [GitBucket](https://github.com/gitbucket/gitbucket) pull requests and reviews them with a local/self-hosted LLM through an OpenAI-compatible API (Ollama, LM Studio, vLLM, etc.) — a CodeRabbit-like reviewer you run entirely on your own infrastructure.

> 日本語版: [README.jp.md](README.jp.md)

## Features

- **Polling** — periodically scans configured repositories for open pull requests and detects new PRs and pushes to existing PRs (no double-reviewing thanks to persisted state).
- **Summary** — generates a Japanese/English summary of the change.
- **Per-repository review perspectives** — checks defined in a `.review.yml` file at the repository root.
- **Monorepo support** — perspectives can be scoped per folder via glob patterns (e.g. different rules for `frontend/**` vs `backend/**`).
- **Whole-repository consistency check (multi-pass)** — when a diff alone isn't enough to judge correctness (e.g. a call site changed without seeing the callee), the LLM can request additional files; the tool fetches them and re-prompts, up to a configurable number of passes.
- **Not UTF-8 only** — source files are decoded with automatic charset detection (Shift_JIS, EUC-JP, UTF-8, ...), since real-world codebases are not always UTF-8.
- **Resilient diff retrieval** — uses JGit against GitBucket's git smart HTTP endpoint as the primary diff source (GitBucket's REST API has no `pulls/:id/files` or compare endpoint), falling back to concatenated per-commit patches from the REST API if JGit fails.
- **Posts review comments** back to the pull request, plus structured logging.

## Requirements

- Java 21+
- Maven 3.9+
- A running GitBucket instance (tested against 4.46.1) with an API token
- An OpenAI-compatible LLM endpoint (e.g. `ollama serve`, LM Studio, vLLM)

## Build

```bash
mvn package
```

Produces an executable fat-jar at `target/gitbucket-llm-reviewer.jar`.

## Configuration

Copy `config.example.yml` to `config.yml` (git-ignored — never commit real tokens) and fill in the values:

```yaml
gitbucket:
  baseUrl: http://localhost:8080
  token: "xxxxxxxx"
  gitUsername: ""     # for JGit fetch (optional). If blank, the API token is tried as Basic auth user/password
  gitPassword: ""
repositories:
  - owner: root
    name: sample-repo
polling:
  intervalSeconds: 60
llm:
  baseUrl: http://localhost:11434/v1
  model: qwen2.5-coder:14b
  apiKey: ""          # only needed by some OpenAI-compatible servers
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

### Repository-side `.review.yml`

Place a `.review.yml` at the root of each reviewed repository (copy from `review.example.yml`). If it's missing or unparsable, a sensible default set of perspectives is used instead.

```yaml
language: ja
perspectives:                 # perspectives applied to the whole repository
  - "Security concerns (injection, missing authorization checks)"
  - "Consistency with existing naming/design"
paths:                        # monorepo support: extra perspectives per folder (glob)
  "frontend/**":
    perspectives:
      - "Missing React hooks dependencies"
      - "XSS (dangerouslySetInnerHTML, etc.)"
    inherit: true              # also apply the common perspectives above (default: true)
  "backend/**":
    perspectives:
      - "Transaction boundary correctness"
      - "N+1 queries"
exclude:                       # glob patterns excluded from the diff
  - "**/*.min.js"
contextFiles:                  # files always included as context (optional)
  - "README.md"
maxComments: 10
```

## Usage

```bash
java -jar target/gitbucket-llm-reviewer.jar --config config.yml
```

Flags:

| Flag | Description |
|---|---|
| `--config <path>` | Path to `config.yml` (default: `./config.yml`) |
| `--once` | Scan once and exit, instead of polling forever |
| `--dry-run` | Log the generated review comment instead of posting it to GitBucket |

Without `--once`, the process keeps running and polls every `polling.intervalSeconds`. It stops gracefully on SIGTERM (waits for the in-flight scan to finish, then releases JGit repository handles).

## How it works

1. `PollingService` lists open PRs for each configured repository and asks `ReviewOrchestrator` to review any PR whose head commit hasn't been reviewed yet.
2. `ReviewOrchestrator` loads `.review.yml` (via GitBucket contents API, falling back to JGit), computes the diff (JGit merge-base diff primary, per-commit-patch concatenation as fallback), resolves the perspectives to apply (common + monorepo path groups), and gathers optional context files.
3. The LLM is prompted with the diff, perspectives, and repository file list. If it replies `need_more_context`, the requested files are fetched (deduplicated, size-capped) and it's re-prompted, up to `review.maxPasses` times.
4. The final summary and findings are formatted into Markdown and posted as a PR comment. The reviewed head SHA is persisted so the same commit is never reviewed twice.

## Known limitations

- GitBucket's REST API has no `pulls/:id/files` or compare endpoint, so the primary diff path depends on JGit being able to fetch over git smart HTTP. If that authentication doesn't work out of the box, set `gitUsername`/`gitPassword` explicitly in `config.yml`.
- The API-based diff fallback concatenates per-commit patches and is an approximation, not an exact merge-base diff.

## License

Licensed under the [Apache License, Version 2.0](LICENSE).
