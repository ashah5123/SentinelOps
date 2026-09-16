import type { ApiClient } from "./client";
import type { AgentProposal } from "./types";

export function listRecentProposals(
  client: ApiClient,
  signal?: AbortSignal,
): Promise<AgentProposal[]> {
  return client.get("/api/v1/proposals", signal);
}

export function getIncidentProposals(
  client: ApiClient,
  incidentId: string,
  signal?: AbortSignal,
): Promise<AgentProposal[]> {
  return client.get(`/api/v1/incidents/${incidentId}/proposals`, signal);
}

export function approveProposal(
  client: ApiClient,
  id: string,
  reviewNote?: string,
): Promise<AgentProposal> {
  return client.post(`/api/v1/proposals/${id}/approve`, reviewNote ? { reviewNote } : {});
}

export function rejectProposal(
  client: ApiClient,
  id: string,
  reviewNote?: string,
): Promise<AgentProposal> {
  return client.post(`/api/v1/proposals/${id}/reject`, reviewNote ? { reviewNote } : {});
}
