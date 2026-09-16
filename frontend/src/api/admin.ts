import type { ApiClient } from "./client";
import type { AuditEvent, DeadLetterReplayResult, PageResponse } from "./types";

export interface AuditQueryParams {
  actorId?: string;
  actorType?: string;
  action?: string;
  incidentId?: string;
  occurredFrom?: string;
  occurredTo?: string;
  page?: number;
  size?: number;
}

function toQueryString(params: AuditQueryParams): string {
  const search = new URLSearchParams();
  if (params.actorId) search.set("actorId", params.actorId);
  if (params.actorType) search.set("actorType", params.actorType);
  if (params.action) search.set("action", params.action);
  if (params.incidentId) search.set("incidentId", params.incidentId);
  if (params.occurredFrom) search.set("occurredFrom", params.occurredFrom);
  if (params.occurredTo) search.set("occurredTo", params.occurredTo);
  if (params.page !== undefined) search.set("page", String(params.page));
  if (params.size !== undefined) search.set("size", String(params.size));
  const qs = search.toString();
  return qs ? `?${qs}` : "";
}

export function searchAuditEvents(
  client: ApiClient,
  params: AuditQueryParams,
  signal?: AbortSignal,
): Promise<PageResponse<AuditEvent>> {
  return client.get(`/api/v1/admin/audit-events${toQueryString(params)}`, signal);
}

/** The only dead-letter topics eligible for replay — mirrors DeadLetterReplayService.java. */
export const ELIGIBLE_DEAD_LETTER_TOPICS = [
  "telemetry.anomaly.v1.dlq",
  "incident.evidence.correlated.v1.dlq",
  "alert.ingested.v1.dlq",
] as const;

export function replayDeadLetterTopic(
  client: ApiClient,
  topic: string,
  maxRecords = 20,
): Promise<DeadLetterReplayResult> {
  return client.post(
    `/api/v1/admin/dead-letter-topics/${encodeURIComponent(topic)}/replay?maxRecords=${maxRecords}`,
  );
}
