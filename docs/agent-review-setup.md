# Agent Guide: Setting Up `.review.yml` and `.review/` for This Repository

> 日本語版: [agent-review-setup.jp.md](agent-review-setup.jp.md)

This file is a **self-contained instruction set for an AI coding agent** (e.g. Claude Code) working inside a repository that will be reviewed by [gitbucket-llm-reviewer](https://github.com/takahino/coderabitlikelocalllm), a self-hosted CodeRabbit-like reviewer for GitBucket pull requests.

## How to use this guide

A human points an AI coding agent at this file — by copying it into the target repository, or pasting its contents into the prompt — and asks something like: *"Follow this guide and create `.review.yml` (and `.review/` if needed) for this repository."* The agent should then read this repository's actual code and docs, and produce a `.review.yml` (and optionally `.review/*.md` files) tailored to what it finds — not a generic template.

This guide does not depend on any file from the reviewer tool's own repository (`review.example.yml`, source code, etc.) being present here — everything needed is explained below.

## Why this matters: silent skip on misconfiguration

The reviewer tool reads `.review.yml` from the **root** of this repository before reviewing any pull request. If the file is missing, is not valid YAML, or resolves to zero `perspectives`, **the review for this repository is silently skipped — no error is raised anywhere the repository owner would see it.** There is no fallback to default perspectives. This means a subtly broken `.review.yml` (e.g. a typo'd top-level key) can silently disable reviews indefinitely. Because of this, the self-check section below is not optional — treat it as part of the task.

## What `.review.yml` is (and is not)

- It must be placed at the **repository root** as `.review.yml` (leading dot). A copy in a subfolder is ignored — monorepo scoping is done via the `paths:` key described below, not by placing multiple `.review.yml` files.
- It defines **review perspectives** (what the LLM should look for) and a few supporting settings — not connection/credentials/model settings. Those live in the reviewer tool's own `config.yml`, which is not part of this repository and not something this guide's agent should touch.
- Unknown top-level or nested keys are **silently ignored**, not rejected — a typo'd key name does not cause a parse error, it just quietly does nothing. Double-check key spelling against the schema table below.

## What `.review/` is (and is not)

- An optional folder, sibling to `.review.yml` at the repository root, holding Markdown files referenced by name from a `perspectives` entry's `context:` list (see schema below). These files are always passed to the LLM in full, whenever the corresponding perspective applies — no search/retrieval involved.
- Organize it into subfolders by category, e.g. `domain-knowledge/`, `architecture/`, `dsl/`, `coding-rules/`, `must-follow/`. Folder names and depth are free-form.
- It is **not** used for anything else. In particular, it has nothing to do with the reviewer tool's own local state files (e.g. a `review-state.json` or a RAG vector index) — those live on the machine running the reviewer tool, outside this repository, and this guide's agent should never create or reference them here.

## Schema reference

All fields are optional unless noted; omitted fields take the listed default.

| Key | Type | Default | Meaning |
|---|---|---|---|
| `language` | string | `"ja"` | Language the LLM should write the review comment in. The tool's prompts are Japanese-oriented; other values are not fully supported yet. |
| `perspectives` | list | `[]` (empty → review skipped) | Review perspectives applied to the whole repository. See "Perspective entry format" below. |
| `paths` | map of glob → path config | `{}` | Per-folder additional perspectives/knowledge base for monorepos. Keys are glob patterns matched against changed-file paths using Java NIO `PathMatcher` `glob:` syntax (not regex) — e.g. `"frontend/**"`. |
| `paths.<glob>.perspectives` | list | `[]` | Perspectives added (not substituted) for files matching this glob. |
| `paths.<glob>.inherit` | boolean | `true` | If `true`, files matching this glob also get the common `perspectives`/`knowledgeBase` above. If `false`, only this glob's own settings apply to those files. |
| `paths.<glob>.knowledgeBase` | list of paths | `[]` | Extra RAG-indexed documents (see `knowledgeBase` below) scoped to this glob. |
| `exclude` | list of globs | `[]` | Files excluded from the diff entirely — they never appear in the change list and never trigger `paths` matching. Use for build artifacts, minified files, lockfiles. |
| `contextFiles` | list of paths | `[]` | Files (relative to repo root) always sent to the LLM in full on every review, regardless of perspective. Use sparingly for small, universally-relevant files (e.g. `README.md`). |
| `knowledgeBase` | list of paths | `[]` | Coding-standard documents vector-searched for RAG context. **Only takes effect if the reviewer tool's `config.yml` has `rag.enabled: true`** — otherwise silently unused. Different from `perspectives[].context`: only similar chunks are surfaced, not the whole file. |
| `maxComments` | integer | `10` | Maximum number of findings *displayed* in the posted comment (does not limit how many the LLM detects). |

### Perspective entry format

Each item in `perspectives` (or `paths.<glob>.perspectives`) is either:

1. A plain string — just the perspective text, no extra context. Write it as free text: an instruction ("check whether...") or a noun phrase ("N+1 query risk") both work.
2. A mapping with `perspective` (the text) and `context` (a list of filenames resolved relative to `.review/`) — use this form when the perspective needs a dedicated reference document from `.review/`.

```yaml
perspectives:
  - "Security concerns (injection, missing authorization checks)"
  - perspective: "Custom DSL validation rule consistency"
    context:
      - "dsl/spec.md"                      # resolves to .review/dsl/spec.md
      - "must-follow/security-checklist.md"
```

### Full example

```yaml
language: en
perspectives:
  - "Security concerns (injection, missing authorization checks)"
  - "Consistency with existing naming/design conventions"
  - perspective: "Custom DSL validation rule consistency"
    context:
      - "dsl/spec.md"
paths:
  "frontend/**":
    perspectives:
      - "Missing React hooks dependencies"
      - "XSS (dangerouslySetInnerHTML, etc.)"
    inherit: true
    knowledgeBase:
      - "frontend/docs/coding-standards.md"
  "backend/**":
    perspectives:
      - "Transaction boundary correctness"
      - "N+1 queries"
    knowledgeBase:
      - "backend/docs/coding-standards.md"
exclude:
  - "**/*.min.js"
contextFiles:
  - "README.md"
knowledgeBase:
  - "docs/coding-standards.md"
maxComments: 10
```

```
.review.yml
.review/
├── domain-knowledge/
│   └── payment-flow.md
├── architecture/
│   └── overview.md
├── dsl/
│   └── spec.md
├── coding-rules/
│   └── naming.md
└── must-follow/
    └── security-checklist.md
```

## Step-by-step procedure for the agent

1. **Survey the repository.** Identify the primary language(s)/framework(s), whether it's a monorepo (multiple independently-structured top-level folders, e.g. `frontend/`, `backend/`), any existing coding-standard docs (`CONTRIBUTING.md`, `docs/coding-standards.md`, ADRs), and any existing lint/security tooling config — these hint at what already matters to this codebase.
2. **Decide `language`.** Match the language the repository's own docs/comments are written in, unless the human instructed otherwise.
3. **Draft the common `perspectives`.** This is the most important step — avoid generic filler like "check for bugs" or "write good code". Ground each perspective in something concrete about *this* codebase. A useful framework:
   - **Stack-specific security risks** — e.g. missing `@PreAuthorize`/authorization checks in a Spring MVC app, `dangerouslySetInnerHTML` misuse in React, raw SQL string concatenation.
   - **Consistency with existing patterns** — naming conventions, layering, error-handling style already established in the codebase.
   - **Domain/business-rule correctness** — invariants specific to this repository's domain that a diff could silently violate.
   - **Known stack-specific performance pitfalls** — N+1 queries for an ORM-backed backend, unnecessary re-renders for a React frontend, etc.
   Every perspective should be something a reviewer could plausibly point at and say "the diff fails/passes this" — not aspirational advice.
4. **Add `paths` if this is a monorepo.** One glob entry per structurally distinct area, with perspectives specific to that area's stack. Decide `inherit` per entry — usually leave it `true` unless the area is different enough that the common perspectives are noise there.
5. **Add `exclude`** for build output, generated code, minified assets, and lockfiles that would only add noise to the diff.
6. **Add `contextFiles`** sparingly — only small files that are genuinely useful on every single review (commonly just `README.md`, if at all).
7. **Add `knowledgeBase`** only if the human indicates RAG will be enabled (`config.yml`'s `rag.enabled: true`) and there are existing coding-standard documents worth indexing; otherwise omit it.
8. **Create `.review/` content as needed.** For any perspective that depends on domain knowledge, an internal DSL/spec, or a must-follow checklist that the LLM can't reasonably infer from the diff alone, write (or copy from existing repo docs) a Markdown file under an appropriately named `.review/<category>/` subfolder, and reference it via that perspective's `context:` list. Do not invent facts — base these files on what actually exists in the repository (existing docs, code comments, specs); if no such source material exists, skip the `context:` addition rather than fabricating content.

## Self-check before finishing (required — see "silent skip" warning above)

- [ ] `.review.yml` is at the **repository root**, not in a subfolder.
- [ ] It is valid YAML and `perspectives` resolves to at least one entry.
- [ ] Every top-level key used is spelled exactly as in the schema table (`language`, `perspectives`, `paths`, `exclude`, `contextFiles`, `knowledgeBase`, `maxComments`) — unknown keys are silently ignored, not rejected.
- [ ] Every filename listed in a `context:` list actually exists under `.review/` at that resolved path.
- [ ] Every `paths` key is a glob that actually matches real files in this repository (glob syntax, not regex).
- [ ] Every path listed in `contextFiles`/`knowledgeBase` actually exists in the repository.

## Anti-patterns to avoid

- Vague, generic perspectives ("write clean code", "check for bugs") that don't reference anything specific to this repository.
- Referencing a `context`/`contextFiles`/`knowledgeBase` path that doesn't exist.
- Placing `.review.yml` anywhere other than the repository root.
- Leaving `perspectives` empty — the repository will simply never be reviewed, with no visible error.
