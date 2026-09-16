package com.sentinelops.incident.ai.retrieval;

import java.util.List;

/**
 * Bounded top-k similarity search over runbook chunks. Never returns the entire corpus — every
 * implementation must respect {@code topK} and {@code minScore}. See {@code
 * PgVectorRunbookRetriever} (preferred/production — PostgreSQL + pgvector) and {@code
 * InMemoryRunbookRetriever} (the documented repository-local alternative, used by tests and the
 * evaluation harness so neither needs a running Postgres instance).
 */
public interface RunbookRetriever {

  List<RetrievedChunk> search(String query, int topK, double minScore);
}
