import type { ApiClient } from "./client";
import type {
  AlertCorrelation,
  AlertEvent,
  AlertNotification,
  ConnectorHealth,
  Escalation,
} from "./types";

export function getIncidentAlerts(
  client: ApiClient,
  incidentId: string,
  signal?: AbortSignal,
): Promise<AlertEvent[]> {
  return client.get(`/api/v1/incidents/${incidentId}/alerts`, signal);
}

export function getIncidentAlertCorrelations(
  client: ApiClient,
  incidentId: string,
  signal?: AbortSignal,
): Promise<AlertCorrelation[]> {
  return client.get(`/api/v1/incidents/${incidentId}/alert-correlations`, signal);
}

export function getIncidentNotifications(
  client: ApiClient,
  incidentId: string,
  signal?: AbortSignal,
): Promise<AlertNotification[]> {
  return client.get(`/api/v1/incidents/${incidentId}/notifications`, signal);
}

export function getIncidentEscalations(
  client: ApiClient,
  incidentId: string,
  signal?: AbortSignal,
): Promise<Escalation[]> {
  return client.get(`/api/v1/incidents/${incidentId}/escalations`, signal);
}

export function getConnectorHealth(
  client: ApiClient,
  signal?: AbortSignal,
): Promise<ConnectorHealth[]> {
  return client.get("/api/v1/admin/alerts/connectors", signal);
}
