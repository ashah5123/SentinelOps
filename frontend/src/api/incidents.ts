import type { ApiClient } from "./client";
import type {
  AuditEvent,
  Incident,
  IncidentQueryParams,
  IncidentSummary,
  PageResponse,
  TimelineEntry,
} from "./types";

function toQueryString(params: IncidentQueryParams): string {
  const search = new URLSearchParams();
  if (params.status) search.set("status", params.status);
  if (params.severity) search.set("severity", params.severity);
  if (params.affectedService) search.set("affectedService", params.affectedService);
  if (params.detectedFrom) search.set("detectedFrom", params.detectedFrom);
  if (params.detectedTo) search.set("detectedTo", params.detectedTo);
  if (params.assignee) search.set("assignee", params.assignee);
  if (params.unassigned) search.set("unassigned", "true");
  if (params.page !== undefined) search.set("page", String(params.page));
  if (params.size !== undefined) search.set("size", String(params.size));
  if (params.sort) search.set("sort", params.sort);
  const qs = search.toString();
  return qs ? `?${qs}` : "";
}

export function listIncidents(
  client: ApiClient,
  params: IncidentQueryParams,
  signal?: AbortSignal,
): Promise<PageResponse<Incident>> {
  return client.get(`/api/v1/incidents${toQueryString(params)}`, signal);
}

export function getIncidentSummary(
  client: ApiClient,
  params: IncidentQueryParams,
  signal?: AbortSignal,
): Promise<IncidentSummary> {
  return client.get(`/api/v1/incidents/summary${toQueryString(params)}`, signal);
}

export function getIncident(
  client: ApiClient,
  id: string,
  signal?: AbortSignal,
): Promise<Incident> {
  return client.get(`/api/v1/incidents/${id}`, signal);
}

export function getIncidentTimeline(
  client: ApiClient,
  id: string,
  signal?: AbortSignal,
): Promise<TimelineEntry[]> {
  return client.get(`/api/v1/incidents/${id}/timeline`, signal);
}

export function getIncidentAuditEvents(
  client: ApiClient,
  id: string,
  signal?: AbortSignal,
): Promise<PageResponse<AuditEvent>> {
  return client.get(`/api/v1/incidents/${id}/audit-events`, signal);
}

export interface CreateIncidentInput {
  title: string;
  description?: string;
  severity: string;
  source: string;
  affectedService: string;
  detectedAt: string;
}

export function createIncident(
  client: ApiClient,
  input: CreateIncidentInput,
  idempotencyKey: string,
): Promise<Incident> {
  return client.post("/api/v1/incidents", input, { idempotencyKey });
}

export function transitionIncident(
  client: ApiClient,
  id: string,
  status: string,
  reason: string,
): Promise<Incident> {
  return client.post(`/api/v1/incidents/${id}/transitions`, { status, reason });
}

export function assignIncident(
  client: ApiClient,
  id: string,
  assigneeId: string | null,
): Promise<Incident> {
  return client.put(`/api/v1/incidents/${id}/assignee`, { assigneeId });
}
