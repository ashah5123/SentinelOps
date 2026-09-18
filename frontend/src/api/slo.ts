import type { ApiClient } from "./client";
import type { SloStatus } from "./types";

export function getSloStatus(client: ApiClient, signal?: AbortSignal): Promise<SloStatus[]> {
  return client.get("/api/v1/slo/status", signal);
}
