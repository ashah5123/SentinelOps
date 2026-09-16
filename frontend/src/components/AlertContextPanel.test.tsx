import { render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { AlertContextPanel } from "./AlertContextPanel";
import type { AlertCorrelation, AlertEvent, AlertNotification, Escalation } from "../api/types";

const stableClient = {};
vi.mock("../api/ApiClientProvider", () => ({ useApiClient: () => stableClient }));

const getIncidentAlertsMock = vi.fn();
const getIncidentAlertCorrelationsMock = vi.fn();
const getIncidentNotificationsMock = vi.fn();
const getIncidentEscalationsMock = vi.fn();
vi.mock("../api/alerts", () => ({
  getIncidentAlerts: (...args: unknown[]) => getIncidentAlertsMock(...args),
  getIncidentAlertCorrelations: (...args: unknown[]) => getIncidentAlertCorrelationsMock(...args),
  getIncidentNotifications: (...args: unknown[]) => getIncidentNotificationsMock(...args),
  getIncidentEscalations: (...args: unknown[]) => getIncidentEscalationsMock(...args),
}));

const alert: AlertEvent = {
  id: "alert-1",
  connectorType: "ALERTMANAGER",
  source: "alertmanager",
  fingerprint: "abcdef0123456789",
  fingerprintVersion: 1,
  status: "FIRING",
  alertName: "HighCpu",
  summary: "CPU high",
  severity: "SEV2",
  service: "checkout-api",
  environment: "production",
  region: null,
  sourceTimestamp: "2026-01-01T00:00:00Z",
  ingestedAt: "2026-01-01T00:00:05Z",
  rawPayloadHash: "hash",
};

const correlation: AlertCorrelation = {
  id: "corr-1",
  alertEventId: "alert-2",
  ruleId: "service-environment-window",
  ruleVersion: 1,
  matchedFields: "{}",
  explanation: "Exactly one open incident shares service and environment.",
  correlatedAt: "2026-01-01T00:00:10Z",
};

const notification: AlertNotification = {
  id: "notif-1",
  channel: "WEBHOOK",
  routingRuleId: "sev1-immediate",
  routingRuleVersion: 1,
  status: "SENT",
  attemptCount: 1,
  lastError: null,
  createdAt: "2026-01-01T00:00:15Z",
  sentAt: "2026-01-01T00:00:16Z",
};

const escalation: Escalation = {
  id: "esc-1",
  routingRuleId: "sev1-immediate",
  routingRuleVersion: 1,
  scheduledAt: "2026-01-01T00:05:00Z",
  status: "CANCELLED",
  deliveredAt: null,
  cancelledAt: "2026-01-01T00:02:00Z",
  cancelledReason: "Incident transitioned to INVESTIGATING before escalation fired",
};

describe("AlertContextPanel", () => {
  beforeEach(() => {
    getIncidentAlertsMock.mockReset();
    getIncidentAlertCorrelationsMock.mockReset();
    getIncidentNotificationsMock.mockReset();
    getIncidentEscalationsMock.mockReset();
  });

  it("shows alerts, correlations, notifications, and escalations for an authorized viewer", async () => {
    getIncidentAlertsMock.mockResolvedValue([alert]);
    getIncidentAlertCorrelationsMock.mockResolvedValue([correlation]);
    getIncidentNotificationsMock.mockResolvedValue([notification]);
    getIncidentEscalationsMock.mockResolvedValue([escalation]);

    render(<AlertContextPanel incidentId="incident-1" canSeeEscalations={true} />);

    await screen.findByText(/HighCpu/);
    expect(screen.getByText(/service-environment-window/)).toBeInTheDocument();
    expect(screen.getByText(/WEBHOOK — SENT/)).toBeInTheDocument();
    expect(screen.getByText(/CANCELLED/)).toBeInTheDocument();
    expect(screen.getByText(/Incident transitioned to INVESTIGATING/)).toBeInTheDocument();
  });

  it("never requests or renders escalations for a non-admin viewer", async () => {
    getIncidentAlertsMock.mockResolvedValue([]);
    getIncidentAlertCorrelationsMock.mockResolvedValue([]);
    getIncidentNotificationsMock.mockResolvedValue([]);
    getIncidentEscalationsMock.mockResolvedValue([escalation]);

    render(<AlertContextPanel incidentId="incident-1" canSeeEscalations={false} />);

    await screen.findByText(/no alerts have been ingested/i);
    expect(screen.queryByText(/Escalations/)).not.toBeInTheDocument();
    expect(getIncidentEscalationsMock).not.toHaveBeenCalled();
  });
});
