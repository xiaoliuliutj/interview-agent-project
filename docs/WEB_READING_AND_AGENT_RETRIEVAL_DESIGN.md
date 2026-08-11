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

The current implementation returns the preview to the authenticated client and sends it through the normal knowledge-base upload route only after the user clicks confirmation. The server determines the owner; a future hardening step can replace this with a short-lived server-side preview token.

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
- Automatic web retrieval accepts only official/technical documentation domains, performs the same URL, DNS, redirect, size, and HTML checks as manual imports, and degrades to normal RAG when search or fetching fails.
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
