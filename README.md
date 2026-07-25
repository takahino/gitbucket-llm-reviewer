# gitbucket-llm-reviewer

A standalone Java 21 application that polls [GitBucket](https://github.com/gitbucket/gitbucket) pull requests and reviews them with a local/self-hosted LLM through an OpenAI-compatible API (Ollama, LM Studio, vLLM, etc.) — a CodeRabbit-like reviewer you run entirely on your own infrastructure.

> 日本語版: [README.jp.md](README.jp.md)

## Features

- **Polling** — periodically scans configured repositories for open pull requests and detects new PRs and pushes to existing PRs (no double-reviewing thanks to persisted state).
- **Summary** — generates a detailed, per-file Japanese/English summary of the change (with code excerpts for notable changes), posted as its own comment separate from the findings.
- **Per-repository review perspectives** — checks defined in a `.review.yml` file at the repository root.
- **Monorepo support** — perspectives can be scoped per folder via glob patterns (e.g. different rules for `frontend/**` vs `backend/**`).
- **Whole-repository consistency check (multi-pass)** — when a diff alone isn't enough to judge correctness (e.g. a call site changed without seeing the callee), the LLM can request additional files; the tool fetches them and re-prompts, up to a configurable number of passes.
- **RAG context augmentation (optional)** — with `rag.enabled: true`, langchain4j is used to vector-search the whole repository and coding-standard documents (`.review.yml`'s `knowledgeBase`) and surface relevant code/standard excerpts as "reference information" up front. This complements rather than replaces the multi-pass request-based fetch above, and the review still proceeds if the embedding server is unreachable.
- **Pseudo inline comments** — GitBucket has no API for commenting directly on a diff line, so findings quote the surrounding code from the diff instead, getting close to an inline-comment experience.
- **Incremental review** — the head SHA from the last successful review is persisted; when new commits are pushed, only the diff since that review is sent to the LLM instead of the whole PR (falls back to a full-PR diff if the previous head can no longer be resolved).
- **LLM call retries** — connection failures, timeouts, and 5xx responses from the LLM server are retried with exponential backoff (4xx responses are not retried).
- **Not UTF-8 only** — source files are decoded with automatic charset detection (Shift_JIS, EUC-JP, UTF-8, ...), since real-world codebases are not always UTF-8.
- **Resilient diff retrieval** — uses JGit against GitBucket's git smart HTTP endpoint as the primary diff source (GitBucket's REST API has no `pulls/:id/files` or compare endpoint), falling back to concatenated per-commit patches from the REST API if JGit fails.
- **Posts review comments** back to the pull request, plus structured logging.

## Requirements

- Java 21+
- Maven 3.9+
- A running GitBucket instance (tested against 4.46.1) with an API token
- An OpenAI-compatible LLM endpoint (e.g. `ollama serve`, LM Studio, vLLM)
- (Optional) An embedding model if RAG is enabled (e.g. `ollama pull nomic-embed-text`)

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
  maxTokens: 16384
  timeoutSeconds: 600
  retryMaxAttempts: 3   # max attempts (including the first) on LLM call failure
  retryBackoffMs: 2000  # wait time between retries in ms, doubles each attempt
review:
  maxDiffChars: 200000
  maxAdditionalFiles: 12
  maxFileChars: 80000
  maxPasses: 5
rag:
  enabled: false                        # set true to enable vector-search context augmentation
  embeddingProvider: ollama             # ollama | openai-compatible
  embeddingBaseUrl: http://localhost:11434
  embeddingModel: nomic-embed-text      # pull it first, e.g. `ollama pull nomic-embed-text`
  embeddingApiKey: ""                   # only for openai-compatible
  topK: 10
  minScore: 0.65
  chunkSize: 1000
  chunkOverlap: 100
  maxIndexFiles: 10000
  includeExtensions: [".java", ".kt", ".ts", ".tsx", ".py", ".go", ".md"]
  indexDir: ./data/rag-index
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
paths:                        # monorepo support: extra perspectives/coding standards per folder (glob)
  "frontend/**":
    perspectives:
      - "Missing React hooks dependencies"
      - "XSS (dangerouslySetInnerHTML, etc.)"
    inherit: true              # also apply the common perspectives/knowledgeBase above (default: true)
    knowledgeBase:
      - "frontend/docs/coding-standards.md"
  "backend/**":
    perspectives:
      - "Transaction boundary correctness"
      - "N+1 queries"
    knowledgeBase:
      - "backend/docs/coding-standards.md"
exclude:                       # glob patterns excluded from the diff
  - "**/*.min.js"
contextFiles:                  # files always included as context (optional)
  - "README.md"
knowledgeBase:                 # coding-standard documents to vector-search (optional, used only when rag.enabled=true)
  - "docs/coding-standards.md"
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
3. If `rag.enabled: true`, the diff is used as a query to vector-search the repository code (indexed fully on first run, incrementally afterward) and coding-standard documents (`.review.yml`'s `knowledgeBase`), surfacing related chunks as "reference information" (falls back to empty and continues the review if the embedding server is unreachable).
4. The LLM is prompted with the diff, perspectives, repository file list, and RAG reference information. If it replies `need_more_context`, the requested files are fetched (deduplicated, size-capped) and it's re-prompted, up to `review.maxPasses` times.
5. The final summary and findings are formatted into Markdown and posted as a PR comment. The reviewed head SHA is persisted so the same commit is never reviewed twice.

## Known limitations

- GitBucket's REST API has no `pulls/:id/files` or compare endpoint, so the primary diff path depends on JGit being able to fetch over git smart HTTP. If that authentication doesn't work out of the box, set `gitUsername`/`gitPassword` explicitly in `config.yml`.
- The API-based diff fallback concatenates per-commit patches and is an approximation, not an exact merge-base diff.
- GitBucket's REST API has no equivalent of GitHub's Pull Request Review Comments (commenting directly on a diff line), so findings quote the surrounding code inside the regular issue comment instead. It is not a true inline diff comment.
- Incremental review only works while the previously reviewed head SHA's git object is still resolvable from the local mirror. After a force-push or mirror gc that removes it, the tool falls back to reviewing the full PR diff.
- GitBucket strips HTML tags (including `<details>`) from Markdown for security reasons, so past review comments are not collapsed/folded — every push posts new summary/findings comments and the comment history simply accumulates.
- RAG (`rag.enabled: true`) surfaces "reference information" via vector similarity search; it does not guarantee accurate file retrieval. When precise information is needed (e.g. confirming a callee's implementation), the LLM still falls back to requesting the full file via `need_more_context`.

## License

Licensed under the [Apache License, Version 2.0](LICENSE).
