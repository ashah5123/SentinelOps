import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { AdminConnectorHealth } from "./AdminConnectorHealth";
import type { ConnectorHealth } from "../api/types";

const stableClient = {};
vi.mock("../api/ApiClientProvider", () => ({ useApiClient: () => stableClient }));

const getConnectorHealthMock = vi.fn();
vi.mock("../api/alerts", () => ({
  getConnectorHealth: (...args: unknown[]) => getConnectorHealthMock(...args),
}));

const connectors: ConnectorHealth[] = [
  {
    name: "ALERTMANAGER",
    enabled: true,
    lastSuccessfulIngestion: "2026-01-01T00:00:00Z",
    recentFailureCount: 0,
    lastSuccessfulNotification: "2026-01-01T00:00:05Z",
    deadLetterCount: 0,
    configurationValid: true,
  },
  {
    name: "GENERIC_WEBHOOK",
    enabled: true,
    lastSuccessfulIngestion: null,
    recentFailureCount: 2,
    lastSuccessfulNotification: "2026-01-01T00:00:05Z",
    deadLetterCount: 1,
    configurationValid: true,
  },
];

describe("AdminConnectorHealth", () => {
  it("renders every connector's health row", async () => {
    getConnectorHealthMock.mockResolvedValue(connectors);
    render(<AdminConnectorHealth />);

    await screen.findByText("ALERTMANAGER");
    expect(screen.getByText("GENERIC_WEBHOOK")).toBeInTheDocument();
    expect(screen.getAllByText("Never")).toHaveLength(1);
    expect(screen.getByText("2")).toBeInTheDocument();
  });
});
