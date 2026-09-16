package com.sentinelops.incident.ai.retrieval;

/**
 * One runbook passage returned by retrieval, with enough metadata to trace it back to its exact
 * source file and section (see section 5's "store enough metadata to trace every retrieved passage
 * back to its source file and section").
 */
public record RetrievedChunk(
    String chunkId,
    String sourceSlug,
    String sourceTitle,
    String section,
    String content,
    double score) {}
