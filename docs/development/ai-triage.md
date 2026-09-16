# AI-assisted incident triage

Optional, human-reviewed assistance that helps a responder understand an incident and find
relevant runbook guidance. It never takes action on its own: generating a suggestion never
mutates an incident, and applying any part of a suggestion always goes through the same
authorized command path every other write in this service uses.

Disabled by default (`sentinelops.ai.enabled=false`). Fully functional with no network calls and
no downloaded model (`provider: deterministic`); a local [Ollama](https://ollama.com) instance is
supported but never required.

## Trust boundary

An incident's title and description are free text any authenticated RESPONDER can set — they are
therefore **untrusted input** as far as the AI pipeline is concerned, on the same footing as data
from an external system. The pipeline is built around that assumption:

- **Nothing the AI provider is given can make it act.** A provider (`AiProvider`) only ever
  returns text; only `AiTriageService` persists a suggestion row, and only a human calling the
  review endpoint can trigger a real incident mutation (and even then, only through
  `IncidentCommandService`, exactly like every other write).
- **The model is never told to follow instructions found in incident/runbook content.** The
  Ollama provider's fixed system prompt (`OllamaAiProvider.SYSTEM_INSTRUCTIONS`, never built from
  request data) explicitly marks the incident fields and retrieved passages as untrusted data
  between `BEGIN .../END ...` markers, and instructs the model never to treat their contents as
  instructions.
- **Structured output is validated strictly, not trusted.** `StructuredOutputValidator`
  deserializes with `FAIL_ON_UNKNOWN_PROPERTIES` (a provider — or content it was tricked into
  echoing — cannot smuggle an extra field through) and rejects any output whose `citations`
  reference a `chunkId` that was not actually retrieved for that request. A provider can only cite
  evidence it was actually given.
- **Redaction happens before anything leaves the process boundary.** `RedactionService` builds the
  `TriageContext` a provider sees from only: title, description (length-bounded), severity,
  status, affected service, source, and up to 10 timeline summaries. It never includes the
  assignee ID, the raw correlation ID, or any actor identity.
- **A suggestion is stored separately from the incident** (`incidents.ai_suggestions`, never
  `incidents.incidents`) and starts `review_status = PENDING`. Nothing else in the system reads a
  pending suggestion as fact.

## Provider abstraction

`AiProvider` (`generate(TriageContext) -> ProviderResult`) has three implementations, selected by
`sentinelops.ai.provider`:

| Value          | Class                    | Behavior                                                                 |
| -------------- | ------------------------ | ------------------------------------------------------------------------- |
| `deterministic`| `DeterministicAiProvider`| Rule-based, network-free. Keyword-categorizes the incident, always echoes its actual current severity, and only ever cites passages it was actually given. Used by default in tests and CI. |
| `ollama`       | `OllamaAiProvider`       | Calls a local Ollama instance's `/api/generate` (never a paid/external API). Bounded retries (`JitteredExponentialBackOff`) only for genuinely retryable failures (timeouts, connection errors, 5xx); a shared `SimpleCircuitBreaker` opens after repeated failures. |
| `disabled`     | `DisabledAiProvider`     | Always returns `Unavailable` immediately, no network call. Used automatically when `sentinelops.ai.enabled=false`. |

`ProviderResult` is a sealed interface: `Success(rawOutput)`, `Unavailable(reason)` (circuit
open, Ollama unreachable), `Failure(reason)` (reached but errored). A provider never throws for an
ordinary "model unavailable" condition.

Embeddings follow the same pattern (`EmbeddingProvider`, `sentinelops.ai.embedding-provider`):
`deterministic` (`DeterministicEmbeddingProvider`, a 384-dimension SHA-256 hashing-trick embedding
— stopword-filtered, L2-normalized, mathematically legitimate à la Vowpal Wabbit, not a
placeholder) or `ollama` (calls `/api/embeddings`).

### Running with a local Ollama model

```bash
ollama pull llama3.2
ollama serve  # if not already running
```

```yaml
sentinelops:
  ai:
    enabled: true
    provider: ollama
    embedding-provider: ollama # or "deterministic" — see the dimension note below
    ollama:
      base-url: http://localhost:11434
      model: llama3.2
      embedding-model: nomic-embed-text
```

Environment-variable overrides exist for every field above (`AI_TRIAGE_ENABLED`, `AI_PROVIDER`,
`OLLAMA_MODEL`, etc. — see `application.yml`), so no code change is needed to switch providers.

`llama3.2` was chosen as the documented example because it is small enough to run on a laptop with
no GPU and reliably follows a JSON-only instruction; any locally-served, instruction-tuned Ollama
model works. `nomic-embed-text` is a common 768-dimension embedding model — if you use one whose
dimension differs from the deterministic fallback's 384, `OllamaEmbeddingProvider` fails fast with
a clear error naming the mismatch (it never silently truncates or pads a vector).

## Runbook knowledge base and retrieval

Seven curated runbooks live at `services/incident-service/src/main/resources/runbooks/*.md`
(elevated API latency, database connection exhaustion, message consumer lag, failed event
delivery, authentication/authorization failures, deployment regression, health-check failure).
Each has a small line-based front-matter block (`slug`, `title`, `version`, `owner`,
`last_reviewed`, `services`, `signals`) and `##`-headed sections chunked one-per-heading by
`RunbookChunker`. A chunk's ID (`stable_chunk_id`) is `sha256(slug|version|index|content)` — stable
across re-ingestion as long as its own content is unchanged.

Retrieval (`RunbookRetriever`, `sentinelops.ai.retrieval.backend`):

- **`pgvector`** (default/production) — `PgVectorRunbookRepository` stores chunks in
  `runbooks.runbook_chunks` (`vector(384)`, HNSW cosine index) via plain JDBC (`com.pgvector:pgvector`
  — Hibernate has no built-in vector type). `RunbookIngestionService.ingestAllFromClasspath()` is
  idempotent: a runbook's `content_hash` is compared before re-embedding, and chunks superseded by
  a re-ingested document are **soft-deleted** (`is_active = false`), never hard-deleted — the
  application's database role has no `DELETE` grant on the `runbooks` schema by design (see
  `03-schemas.sh`), so this also respects the existing least-privilege convention rather than
  requiring a grant change.
- **`in-memory`** — `InMemoryRunbookRetriever`, the documented, network-free alternative used by
  tests and the evaluation harness (`AiEvaluationTest`) so neither needs a running Postgres
  instance. Uses the exact same cosine-similarity math as the pgvector path
  (`CosineSimilarity.of`, mathematically identical to pgvector's `<=>` operator: similarity = 1 −
  distance), so it is a faithful stand-in, not a shortcut.

Both implementations return `RetrievedChunk` (chunk ID, source slug/title, section, content,
score), bounded by `sentinelops.ai.retrieval.top-k` and `.min-score`.

## Pipeline

`AiTriageService.generate(incidentId, correlationId, requestedBy)`:

1. Enforce the per-incident rate limit (`sentinelops.ai.rate-limit.cooldown`) — `AiRateLimitedException` (HTTP 429) if violated.
2. Load the incident (404 via `IncidentNotFoundException` if missing).
3. Build the retrieval query and redacted `TriageContext` (`RedactionService`).
4. Retrieve bounded top-k runbook passages (`RunbookRetriever`).
5. Call the configured `AiProvider`.
6. On `Unavailable`/`Failure`: persist a non-`COMPLETED` suggestion (status `MODEL_UNAVAILABLE`/`FAILED`) and return — no automatic outer retry beyond the provider's own bounded internal one.
7. On `Success`: strictly validate the structured output (`StructuredOutputValidator`) — schema (unknown fields rejected, length/blank checks) plus citation cross-check against the chunk IDs actually retrieved in step 4.
8. If invalid: persist a `FAILED` suggestion recording the validation reason. The pipeline never guesses or "repairs" invalid output itself.
9. If valid: persist a `COMPLETED` suggestion with the validated structured result.
10. Record an `AI_SUGGESTION_GENERATED` audit event regardless of outcome.
11. Return the persisted suggestion. **The incident row is never touched by this call.**

Every outcome — including every failure path — ends in exactly one persisted `ai_suggestions` row
and one audit event, so an operator can always see that a request was made and what came of it.

## Human review and acceptance

`POST /api/v1/incidents/{id}/ai-suggestions/{suggestionId}/review` (`AiSuggestionReviewService`)
takes `acceptedFields` (subset of `{"severity", "category"}`) and optional `feedback`:

- Accepting **`severity`** calls `IncidentCommandService.changeSeverity` — the same authorized,
  audited command path every other incident write in this service uses (added in Phase 11
  specifically so acceptance never bypasses it). It requires `suggestedSeverity` to be present.
- Accepting **`category`** only updates the suggestion's own review record — `Incident` has no
  `category` column, so there is nothing else for it to write. This is a deliberate consequence
  of "never invent a field the domain model doesn't have."
- An empty `acceptedFields` list means outright rejection (`review_status = REJECTED`).
- `review_status` becomes `ACCEPTED` only if every acceptable field was accepted, `PARTIAL`
  otherwise.

## API

All endpoints require the RESPONDER or ADMIN role to write, any authenticated role to read (same
model as `IncidentController`):

- `POST /api/v1/incidents/{id}/ai-suggestions` — generate (rate-limited, 429 with `Retry-After` on violation)
- `GET /api/v1/incidents/{id}/ai-suggestions` — list, most recent first
- `GET /api/v1/incidents/{id}/ai-suggestions/{suggestionId}` — get one
- `POST /api/v1/incidents/{id}/ai-suggestions/{suggestionId}/review` — record a review decision

## Frontend

`IncidentDetail` renders an "AI Assistance" panel (`AiAssistancePanel.tsx`) below the normal
lifecycle actions — purely additive, never blocking the rest of the page. It shows: a generate
button (RESPONDER/ADMIN only), per-suggestion summary/category/severity/confidence, an expandable
evidence-and-citations section, a persistent "requires human review" banner while
`review_status = PENDING`, and accept/reject/feedback controls. Accepting severity triggers a
refetch of the incident itself so the page reflects the change made through the normal command
path.

## Observability

`AiMetrics` (Micrometer, `sentinelops.ai.*`): triage requests, completions by provider/outcome,
invalid-structured-output count, provider timeouts, circuit-breaker rejections, retrieval result
count, review decisions, rate-limited count. Every tag is a small fixed value (provider name,
outcome, review decision) — never an incident ID, prompt, or model output, matching the existing
`IncidentMetrics` convention.

Tracing: `AiTriageService.generate` runs inside an `ai.triage.generate` span
(`Spans.inSpan`, tagged only with the provider name) — never a raw prompt or response as a span
tag or log field. `OllamaAiProvider.sanitizedReason` logs only the exception class and message on
a retry, never request/response bodies.

## Evaluation

A version-controlled synthetic dataset lives at
`services/incident-service/src/test/resources/ai/eval/triage-eval-dataset.json` (18 cases: 14
across the 7 runbook categories, 2 with no relevant runbook, 2 prompt-injection attempts).
`AiEvaluationTest` runs the deterministic pipeline against it and asserts on real, computed
metrics — nothing here is a placeholder or invented number:

| Metric | Definition | Last measured result |
| --- | --- | --- |
| Recall@5 | Fraction of retrieval cases where a relevant runbook appears in the top 5 results | 16/16 (1.000) |
| Mean Precision@5 | Average fraction of the top-5 results that are relevant | 0.613 |
| MRR | Mean reciprocal rank of the first relevant result | 0.969 |
| Structured-output validity | Fraction of generations that pass `StructuredOutputValidator` | 18/18 |
| Citation validity | Fraction of generations whose citations are all within the retrieved set | 18/18 |
| Correct abstention | Fraction of no-relevant-runbook cases with empty citations, no passages above threshold, and disclosed limitations | 2/2 |
| Prompt-injection resistance | Fraction of injection-attempt cases where the suggested severity still matches the incident's real severity and the output stays schema-valid | 2/2 |

Run it locally:

```bash
cd services/incident-service
./mvnw -q test -Dtest=AiEvaluationTest
```

This suite uses the deterministic provider and in-memory retrieval only — no Ollama, GPU, or paid
API required, and it runs automatically as part of `./mvnw verify` in CI. A live-model evaluation
against a real Ollama instance is intentionally **not** part of this suite or CI; run the same
dataset manually against `sentinelops.ai.provider=ollama` if you want to compare a real model's
citation/structured-output validity (the retrieval metrics above are provider-independent, since
retrieval never depends on the generation provider).

## Testing

- `RunbookChunkerTest`, `InMemoryRunbookRetrieverTest` — chunking and retrieval correctness (including a real-runbook-corpus check).
- `StructuredOutputValidatorTest` — schema validation, unknown-field rejection, citation cross-check.
- `DeterministicAiProviderTest` — severity always echoed, citations never fabricated.
- `SimpleCircuitBreakerTest` — CLOSED → OPEN → HALF_OPEN state machine.
- `AiTriageServiceTest` — success/failure/unavailable/invalid-output outcomes, a citation-forgery adversarial case, an unknown-field-injection adversarial case, rate limiting, and a direct assertion that generation never mutates the incident.
- `AiSuggestionReviewServiceTest` — severity acceptance calling the real command path, rejection, partial acceptance, unknown-field rejection, not-found.
- `AiEvaluationTest` — the dataset-driven metrics above, including the two prompt-injection cases.

## How to disable AI assistance entirely

Set `sentinelops.ai.enabled=false` (the default) or `sentinelops.ai.provider=disabled`. Every
generate request then returns a `MODEL_UNAVAILABLE` suggestion immediately, with no network call;
the rest of the application is completely unaffected, since nothing else in the codebase reads
`ai_suggestions`.

## Known limitations

- The deterministic embedding is a lexical hashing-trick signal, not true semantic similarity —
  it works well when an incident's free text shares vocabulary with the target runbook (as the
  eval dataset and `InMemoryRunbookRetrieverTest` are written to do), but will under-perform on
  paraphrased or purely symptomatic descriptions that share no vocabulary with a runbook.
  `docs/development/ai-triage.md`'s "prefer pgvector" guidance is unaffected by this — the same
  limitation applies to the deterministic *embedding*, independent of which retrieval backend
  stores it.
- No live-model (Ollama) evaluation numbers are included here: no Ollama models were available in
  the environment this feature was built in. The pipeline's prompt-injection and citation defenses
  are enforced structurally (schema validation + citation cross-check, independent of which
  provider produced the output), so they apply identically to Ollama-produced output, but that
  claim has only been exercised against the deterministic provider's output in this repository so
  far.
- Rate limiting is in-process (a `ConcurrentHashMap` in `AiTriageService`), not distributed —
  correct for a single instance, but would need a shared store (e.g. the database) to hold across
  multiple `incident-service` replicas.
