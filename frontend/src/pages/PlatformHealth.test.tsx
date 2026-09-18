import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi, beforeEach } from "vitest";
import { PlatformHealth } from "./PlatformHealth";
import type { SloStatus } from "../api/types";

const stableClient = {};
vi.mock("../api/ApiClientProvider", () => ({ useApiClient: () => stableClient }));

const getSloStatusMock = vi.fn();
vi.mock("../api/slo", () => ({
  getSloStatus: (...args: unknown[]) => getSloStatusMock(...args),
}));

const knownSlo: SloStatus = {
  id: "alert-telemetry-ingestion",
  name: "Alert & telemetry ingestion success rate",
  description: "Fraction of ingestion attempts accepted.",
  objective: 0.995,
  windowDays: 28,
  known: true,
  currentSli: 0.999,
  errorBudgetRemaining: 0.8,
  burnRate: 0.2,
  severity: "OK",
};

const unknownSlo: SloStatus = {
  id: "notification-delivery",
  name: "Notification delivery success rate",
  description: "Fraction of notifications delivered.",
  objective: 0.98,
  windowDays: 28,
  known: false,
  currentSli: null,
  errorBudgetRemaining: null,
  burnRate: null,
  severity: "UNKNOWN",
};

describe("PlatformHealth", () => {
  beforeEach(() => {
    getSloStatusMock.mockReset();
  });

  it("renders a known SLO's computed values", async () => {
    getSloStatusMock.mockResolvedValue([knownSlo]);
    render(<PlatformHealth />);

    await screen.findByText(/Alert & telemetry ingestion success rate/);
    expect(screen.getByText("99.900%")).toBeInTheDocument();
    expect(screen.getByText("0.20x")).toBeInTheDocument();
    expect(screen.getByText("OK")).toBeInTheDocument();
  });

  it("shows Unknown for an SLO Prometheus could not evaluate, without crashing", async () => {
    getSloStatusMock.mockResolvedValue([unknownSlo]);
    render(<PlatformHealth />);

    await screen.findByText(/Notification delivery success rate/);
    expect(screen.getAllByText("Unknown").length).toBeGreaterThan(0);
  });

  it("renders both known and unknown SLOs together without either breaking the page", async () => {
    getSloStatusMock.mockResolvedValue([knownSlo, unknownSlo]);
    render(<PlatformHealth />);

    await screen.findByText(/Alert & telemetry ingestion success rate/);
    await screen.findByText(/Notification delivery success rate/);
  });
});
