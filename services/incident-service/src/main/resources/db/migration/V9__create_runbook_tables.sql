-- Phase 11: curated runbook knowledge base + AI-triage suggestions. The `runbooks` schema and the
-- `vector` extension already exist (Phase 2, infrastructure/docker/postgres/init/) — this is the
-- first migration to actually use them.
--
-- Chunks are soft-deleted (is_active) rather than hard-deleted: the application role only holds
-- SELECT/INSERT/UPDATE on the runbooks schema (see 03-schemas.sh), not DELETE, matching the
-- existing least-privilege convention. Every retrieval query filters WHERE is_active = true.

CREATE TABLE runbooks.runbook_documents (
    id               UUID PRIMARY KEY,
    slug             VARCHAR(100) NOT NULL,
    title            VARCHAR(200) NOT NULL,
    version          INT NOT NULL,
    content_hash     VARCHAR(64) NOT NULL,
    source_path      VARCHAR(255) NOT NULL,
    owner            VARCHAR(100) NOT NULL,
    last_reviewed_at DATE NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL,
    updated_at       TIMESTAMPTZ NOT NULL,

    CONSTRAINT uq_runbook_documents_slug UNIQUE (slug)
);

COMMENT ON TABLE runbooks.runbook_documents IS
    'One row per curated runbook file. version/content_hash drive idempotent re-ingestion — see '
    'RunbookIngestionService.';

-- pgvector: 384 dimensions, matching both the deterministic (hashing-trick) embedding fallback
-- and small local embedding models such as all-MiniLM-L6-v2 / Ollama's nomic-embed-text (768 is
-- also common; 384 was chosen so the deterministic fallback and a real small model can share one
-- schema without a dimension migration — see docs/development/ai-triage.md for the tradeoff).
CREATE TABLE runbooks.runbook_chunks (
    id              UUID PRIMARY KEY,
    document_id     UUID NOT NULL REFERENCES runbooks.runbook_documents (id),
    -- Deterministic, stable across re-ingestion as long as the chunk's own content is unchanged
    -- (sha256 of slug|version|chunk_index|content) — see RunbookChunker.
    stable_chunk_id VARCHAR(64) NOT NULL,
    chunk_index     INT NOT NULL,
    heading         VARCHAR(200),
    content         TEXT NOT NULL,
    token_count     INT NOT NULL,
    embedding       vector(384) NOT NULL,
    is_active       BOOLEAN NOT NULL DEFAULT true,
    created_at      TIMESTAMPTZ NOT NULL,

    CONSTRAINT uq_runbook_chunks_stable_id UNIQUE (stable_chunk_id)
);

CREATE INDEX idx_runbook_chunks_document_id ON runbooks.runbook_chunks (document_id);
CREATE INDEX idx_runbook_chunks_active ON runbooks.runbook_chunks (is_active);
-- Approximate-nearest-neighbor index for cosine similarity search, bounded to only active chunks
-- at query time via the WHERE clause (the index itself covers all rows; pgvector does not support
-- partial vector indexes as of this version, so the query-time filter does the bounding).
CREATE INDEX idx_runbook_chunks_embedding_cosine
    ON runbooks.runbook_chunks USING hnsw (embedding vector_cosine_ops);

COMMENT ON COLUMN runbooks.runbook_chunks.is_active IS
    'Soft-delete flag: set false when a chunk is superseded by re-ingestion of a newer document '
    'version. The application role has no DELETE grant on this schema by design.';

-- Suggestions are stored separately from authoritative incident data (incidents.incidents is
-- never modified by this table) and are themselves immutable except for the review fields, which
-- only an authorized human reviewer sets — see AiTriageService and the review endpoint.
CREATE TABLE incidents.ai_suggestions (
    id                     UUID PRIMARY KEY,
    incident_id            UUID NOT NULL REFERENCES incidents.incidents (id),
    requested_by           VARCHAR(100) NOT NULL,
    correlation_id         VARCHAR(64) NOT NULL,
    provider_name          VARCHAR(50) NOT NULL,
    model_name             VARCHAR(100) NOT NULL,
    prompt_template_version VARCHAR(20) NOT NULL,
    retrieval_config       JSONB NOT NULL,
    status                 VARCHAR(20) NOT NULL
                               CHECK (status IN ('COMPLETED', 'FAILED', 'MODEL_UNAVAILABLE')),
    -- The full validated structured result (summary, suggested category/severity, confidence,
    -- evidence, diagnostic steps, escalation conditions, citations, limitations) as JSON — see
    -- TriageSuggestion.java. Duplicated key fields below only for cheap filtering/reporting.
    structured_result      JSONB,
    suggested_severity     VARCHAR(10),
    suggested_category     VARCHAR(50),
    failure_reason         VARCHAR(500),
    created_at             TIMESTAMPTZ NOT NULL,

    review_status          VARCHAR(20) NOT NULL DEFAULT 'PENDING'
                               CHECK (review_status IN ('PENDING', 'ACCEPTED', 'REJECTED', 'PARTIAL')),
    reviewed_by            VARCHAR(100),
    reviewed_at            TIMESTAMPTZ,
    accepted_fields        JSONB,
    review_feedback        VARCHAR(1000)
);

CREATE INDEX idx_ai_suggestions_incident_id ON incidents.ai_suggestions (incident_id);
CREATE INDEX idx_ai_suggestions_created_at ON incidents.ai_suggestions (incident_id, created_at);

COMMENT ON TABLE incidents.ai_suggestions IS
    'AI-generated triage suggestions. Never authoritative and never mutates incidents.incidents '
    'directly — accepting a field re-uses the normal authorized command path (see '
    'AiTriageController).';
