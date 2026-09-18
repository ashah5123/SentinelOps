import type { ApiClient } from "./client";
import type { RemediationExecution, RemediationRunbook, RemediationStep } from "./types";

export function listRunbooks(
  client: ApiClient,
  signal?: AbortSignal,
): Promise<RemediationRunbook[]> {
  return client.get("/api/v1/runbooks", signal);
}

export function listRecentRemediations(
  client: ApiClient,
  signal?: AbortSignal,
): Promise<RemediationExecution[]> {
  return client.get("/api/v1/remediations", signal);
}

export function getRemediationSteps(
  client: ApiClient,
  id: string,
  signal?: AbortSignal,
): Promise<RemediationStep[]> {
  return client.get(`/api/v1/remediations/${id}/steps`, signal);
}

export function proposeRemediation(
  client: ApiClient,
  request: {
    runbookSlug: string;
    incidentId?: string;
    proposalId?: string;
    dryRun: boolean;
    idempotencyKey: string;
  },
): Promise<RemediationExecution> {
  return client.post("/api/v1/remediations", request);
}

export function approveRemediation(
  client: ApiClient,
  id: string,
  note?: string,
): Promise<RemediationExecution> {
  return client.post(`/api/v1/remediations/${id}/approve`, note ? { note } : {});
}

export function rejectRemediation(client: ApiClient, id: string): Promise<RemediationExecution> {
  return client.post(`/api/v1/remediations/${id}/reject`, {});
}

export function cancelRemediation(client: ApiClient, id: string): Promise<RemediationExecution> {
  return client.post(`/api/v1/remediations/${id}/cancel`, {});
}

export function emergencyStopRemediation(
  client: ApiClient,
  id: string,
): Promise<RemediationExecution> {
  return client.post(`/api/v1/remediations/${id}/emergency-stop`, {});
}
