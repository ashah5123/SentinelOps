import type { ApiClient } from "./client";
import type { AiSuggestion } from "./types";

export function generateAiSuggestion(client: ApiClient, incidentId: string): Promise<AiSuggestion> {
  return client.post(`/api/v1/incidents/${incidentId}/ai-suggestions`);
}

export function listAiSuggestions(
  client: ApiClient,
  incidentId: string,
  signal?: AbortSignal,
): Promise<AiSuggestion[]> {
  return client.get(`/api/v1/incidents/${incidentId}/ai-suggestions`, signal);
}

export interface AiReviewInput {
  acceptedFields: string[];
  feedback?: string;
}

export function reviewAiSuggestion(
  client: ApiClient,
  incidentId: string,
  suggestionId: string,
  input: AiReviewInput,
): Promise<AiSuggestion> {
  return client.post(
    `/api/v1/incidents/${incidentId}/ai-suggestions/${suggestionId}/review`,
    input,
  );
}
