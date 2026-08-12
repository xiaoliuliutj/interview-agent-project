# Web Reading and Three-Layer Retrieval Design

## Goals

1. Let a user submit a public HTTP(S) article URL, preview the extracted Markdown, and confirm it as a user-owned knowledge base.
2. Preserve the source URL, title, fetch time, and content SHA-256 hash.
3. Keep web retrieval bounded to a 120-second timeout and two retries.
4. Extend interview evidence lookup to cache first, then vector RAG, then approved public web retrieval when RAG evidence is insufficient.
5. Expose meaningful progress states while an answer is being processed.

## Import flow

```text
URL -> SSRF/content checks -> fetch -> HTML article extraction -> Markdown
    -> preview with source metadata -> user confirmation -> Java KB persistence
    -> RabbitMQ indexing -> pgvector
```

Single-page imports return Markdown to the authenticated client for explicit
confirmation. Deep crawls use an owner-scoped server preview that expires after
30 minutes. Confirmation submits only the preview token and selected
server-issued page IDs; Java imports its stored Markdown instead of trusting
content posted back by the browser.

## Web safety

- Only `http` and `https` URLs are accepted.
- DNS results for loopback, private, link-local, multicast, reserved, and cloud metadata ranges are rejected.
- Redirects are disabled for the first request and revalidated hop by hop, with a small redirect limit.
- Response size, content type, and extracted text length are bounded.
- A request has a 120-second total timeout and at most two retries for transient network failures.
- HTML is treated as untrusted data. It is never injected as instructions into system prompts and cannot invoke tools.
- Only explicit user URLs are imported. Automatic interview-time retrieval is restricted to public technical pages returned by the configured search provider.

## Three-layer evidence lookup

Each interview session owns a short-lived evidence cache. The lookup order is:

1. Session evidence cache (including source metadata and expiry).
2. pgvector RAG over ready system and user knowledge bases.
3. Public web search/fetch when the RAG result count or score is below policy thresholds.

Web evidence is cached in the same session cache with `sourceType=WEB`. It is used for the current question and later turns, but the cache is cleared when the interview is completed or deleted. Long-term memory and persisted knowledge-base documents are not cleared by this operation.

### Implemented policy

- Cache keys are scoped by interview stage, normalized topic, and selected knowledge-base IDs.
- A cache hit is reused without another embedding or web request.
- Local RAG is considered sufficient only with at least two chunks and a best score of `0.5` or higher; otherwise the Agent searches up to two allowlisted technical-documentation pages.
- Automatic web retrieval accepts only official/technical documentation domains, performs the same URL, DNS, redirect, size, and HTML checks as manual imports, and degrades to normal RAG when search or fetching fails. Interview-time RAG and public-web enrichment have separate 30-second and 15-second ceilings; the entire answer turn has a 150-second ceiling.
- Web content is explicitly labelled untrusted evidence before question generation. It is reference material only and cannot provide instructions or cause tool use.

## Progress states

The upper API exposes a progress phase for the current answer submission. The UI renders at least:

`评估中 → 路由中 → 缓存检索中 → RAG 检索中 → 网页检索中 → 出题中`

The phase is operational feedback only; routing and completion limits remain enforced by the lower Agent state machine.

The Python Agent now stores the live phase per active session and exposes it through `GET /v1/agent/sessions/{sessionId}/progress`. The Java API proxies this as `GET /api/interviews/{sessionId}/agent-status`; the frontend polls it only while an answer or completion request is in progress.

## Persistence

Web-imported knowledge bases store `source_url`, `source_title`, `source_fetched_at`, and `source_hash`. Existing file uploads leave these fields null. Migration `004-web-source-metadata.sql` is idempotent for existing PostgreSQL volumes.

## Rollout order

1. Add lower-layer fetch/search contracts and tests.
2. Add Java preview store, metadata persistence, and user-confirmed import endpoints.
3. Add the knowledge-base URL preview/confirm UI.
4. Add cache/RAG/web evidence fallback and cache cleanup.
5. Add progress phase propagation and UI rendering.
6. Run Python, Java, and frontend checks, then rebuild the deployment images.

## Deep crawl plan (directory pages)

The single-page reader is extended with a bounded crawl task for documentation
indexes. The user supplies one public entry URL and receives a preview of
individual cleaned Markdown pages plus a provenance archive Markdown.

### Hard budgets and the valid-page rule

- Maximum depth is 2: entry page is depth 0, its children depth 1, and their
  children depth 2. No links are expanded from depth 2.
- A task has an absolute deadline of 10 minutes. Every request still has the
  existing 120-second timeout and up to two transient-error retries, bounded by
  the remaining task deadline.
- The effective-page budget is 20. **Only a page that passes extraction,
  minimum-content, relevance, and duplicate checks counts toward these 20
  pages.** A page rejected as empty, boilerplate, off-topic, unsafe, or a
  duplicate is recorded in the task audit but does not consume the effective
  page budget.
- Invalid pages nevertheless consume raw response bytes, time, and an
  attempted-candidate circuit breaker. This prevents an index containing
  thousands of empty links from bypassing resource limits. The default
  attempted-candidate ceiling is 100; the task also stops at 50MB total raw
  response bytes and a 1.5M-character cleaned-text ceiling.
- The task returns all successful pages when a budget is reached and is marked
  `PARTIAL_COMPLETED` with an explicit stop reason.

### Agent planning versus code enforcement

The Agent classifies each fetched page as `CONTENT`, `DIRECTORY`, or
`IRRELEVANT`, scores relevance to the supplied topic/entry, and ranks child
links for expansion. The crawler code remains the authority for depth, domain,
SSRF, retries, byte/time/page budgets, URL normalization, and deduplication;
Agent output can never grant an unsafe URL or bypass a budget.

### Per-page processing

Each fetched HTML page is parsed with deterministic structural cleaning before
any Agent assessment. Navigation, sidebars, footers, scripts, styles, forms,
cookie banners, comments, ads, and repeated boilerplate are discarded. The
remaining headings, paragraphs, lists, code blocks, tables, and meaningful
links are converted to Markdown. Prompt-injection phrases are marked as
untrusted data and excluded from Agent instructions.

Pages are considered valid only when cleaned text is sufficiently rich and
relevant, and its normalized content hash is not already present in the task.
The task stores invalid and duplicate candidates with a reason, but they are
not imported into RAG and do not count toward the 20-page effective budget.

### Output artifacts and import

Every valid page becomes an independent Markdown document with front matter:

```yaml
title: "..."
source_url: "..."
fetched_at: "..."
content_hash: "..."
depth: 1
parent_url: "..."
rag_index_enabled: true
```

The task also produces one `*-sources.md` archive. It contains task statistics,
an HTTP-linked table of contents, each page's source URL/title/hash/depth and
the cleaned content. The archive is marked `rag_index_enabled: false` and is
downloadable but is never sent to the vector-index worker. After preview, the
user can exclude individual valid pages; only confirmed page documents are
uploaded and indexed from the owner-scoped server preview. The archive is
downloaded from the same owner-checked preview and is never persisted as a
KnowledgeBase, so it can never reach the vector worker.

### Failure and recovery states

Candidates use `DISCOVERED`, `FETCHING`, `CLEANING`, `READY`, `INVALID`,
`DUPLICATE`, `EXCLUDED`, and `FAILED`. The task uses `PLANNING`, `CRAWLING`,
`AWAITING_CONFIRMATION`, `IMPORTING`, `COMPLETED`, `PARTIAL_COMPLETED`,
`FAILED`, and `CANCELLED`. A failed child never discards successful siblings.
