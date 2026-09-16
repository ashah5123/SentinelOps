import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { IncidentDetail } from "./IncidentDetail";
import type { Incident } from "../api/types";

vi.mock("react-router-dom", async () => {
  const actual = await vi.importActual<typeof import("react-router-dom")>("react-router-dom");
  return { ...actual, useParams: () => ({ id: "incident-1" }) };
});

const mockUseAuth = vi.fn();
vi.mock("../auth/AuthProvider", () => ({ useAuth: () => mockUseAuth() }));

const stableClient = {};
vi.mock("../api/ApiClientProvider", () => ({ useApiClient: () => stableClient }));

vi.mock("../components/Announcer", () => ({ useAnnounce: () => vi.fn() }));

const sampleIncident: Incident = {
  id: "incident-1",
  incidentNumber: "INC-2026-000001",
  title: "Checkout API elevated errors",
  description: "d",
  severity: "SEV2",
  status: "DETECTED",
  source: "manual-report",
  affectedService: "checkout-api",
  detectedAt: "2026-09-12T18:00:00Z",
  createdAt: "2026-09-12T18:00:00Z",
  updatedAt: "2026-09-12T18:00:00Z",
  resolvedAt: null,
  assigneeId: null,
  correlationId: "corr-1",
  version: 0,
};

const transitionIncidentMock = vi.fn();
vi.mock("../api/incidents", () => ({
  getIncident: () => Promise.resolve(sampleIncident),
  getIncidentTimeline: () => Promise.resolve([]),
  getIncidentAuditEvents: () =>
    Promise.resolve({
      content: [],
      page: 0,
      size: 20,
      totalElements: 0,
      totalPages: 0,
      last: true,
    }),
  transitionIncident: (...args: unknown[]) => transitionIncidentMock(...args),
  assignIncident: () => Promise.resolve(sampleIncident),
}));

vi.mock("../api/ai", () => ({
  listAiSuggestions: () => Promise.resolve([]),
  generateAiSuggestion: () => Promise.resolve(),
  reviewAiSuggestion: () => Promise.resolve(),
}));

vi.mock("../api/alerts", () => ({
  getIncidentAlerts: () => Promise.resolve([]),
  getIncidentAlertCorrelations: () => Promise.resolve([]),
  getIncidentNotifications: () => Promise.resolve([]),
  getIncidentEscalations: () => Promise.resolve([]),
}));

describe("IncidentDetail", () => {
  it("prevents a duplicate submission while a transition is still pending", async () => {
    mockUseAuth.mockReturnValue({ roles: ["RESPONDER"] });
    let resolveTransition!: (value: Incident) => void;
    transitionIncidentMock.mockReturnValue(new Promise((resolve) => (resolveTransition = resolve)));

    render(<IncidentDetail />);

    const button = await screen.findByRole("button", { name: /move to investigating/i });
    const user = userEvent.setup();
    await user.click(button);
    // The button is now disabled while the request is pending — a second click must not fire.
    await user.click(button);

    expect(transitionIncidentMock).toHaveBeenCalledTimes(1);

    resolveTransition(sampleIncident);
    await waitFor(() => expect(button).not.toBeDisabled());
  });

  it("shows a read-only notice instead of action controls for a VIEWER", async () => {
    mockUseAuth.mockReturnValue({ roles: ["VIEWER"] });
    render(<IncidentDetail />);
    await screen.findByText(sampleIncident.title, { exact: false });
    expect(screen.queryByRole("button", { name: /move to/i })).not.toBeInTheDocument();
    expect(screen.getByText(/cannot perform lifecycle actions/i)).toBeInTheDocument();
  });
});
