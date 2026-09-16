import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi, beforeEach } from "vitest";
import { AgentProposals } from "./AgentProposals";
import type { AgentProposal } from "../api/types";

const stableClient = {};
vi.mock("../api/ApiClientProvider", () => ({ useApiClient: () => stableClient }));
vi.mock("../components/Announcer", () => ({ useAnnounce: () => vi.fn() }));

const mockUseAuth = vi.fn();
vi.mock("../auth/AuthProvider", () => ({ useAuth: () => mockUseAuth() }));

const listMock = vi.fn();
const approveMock = vi.fn();
const rejectMock = vi.fn();
vi.mock("../api/proposals", () => ({
  listRecentProposals: (...args: unknown[]) => listMock(...args),
  approveProposal: (...args: unknown[]) => approveMock(...args),
  rejectProposal: (...args: unknown[]) => rejectMock(...args),
}));

const proposal: AgentProposal = {
  id: "proposal-1",
  incidentId: "incident-1",
  actionType: "ACKNOWLEDGE",
  parameters: {},
  reason: "Investigate elevated latency",
  evidenceReferences: [],
  expectedVersion: 0,
  riskClassification: "LOW",
  requestedBy: "agent-1",
  createdAt: "2026-01-01T00:00:00Z",
  expiresAt: "2026-01-02T00:00:00Z",
  status: "PENDING",
  approvedBy: null,
  approvedAt: null,
  reviewNote: null,
  rejectedBy: null,
  rejectedAt: null,
  rejectionNote: null,
  executedAt: null,
  executionResult: null,
  executionError: null,
};

describe("AgentProposals", () => {
  beforeEach(() => {
    listMock.mockReset();
    approveMock.mockReset();
    rejectMock.mockReset();
  });

  it("hides review controls for a read-only viewer", async () => {
    mockUseAuth.mockReturnValue({ roles: ["VIEWER"] });
    listMock.mockResolvedValue([proposal]);
    render(<AgentProposals />);

    await screen.findByText("ACKNOWLEDGE");
    expect(screen.queryByRole("button", { name: /approve/i })).not.toBeInTheDocument();
  });

  it("requires confirmation before approving, then calls the approve endpoint", async () => {
    mockUseAuth.mockReturnValue({ roles: ["RESPONDER"] });
    listMock.mockResolvedValue([proposal]);
    approveMock.mockResolvedValue({ ...proposal, status: "EXECUTED" });
    render(<AgentProposals />);

    await screen.findByText("ACKNOWLEDGE");
    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: /^approve$/i }));

    // Execution must not happen before explicit confirmation.
    expect(approveMock).not.toHaveBeenCalled();

    const dialog = screen.getByRole("alertdialog");
    await user.click(within(dialog).getByRole("button", { name: /approve and execute/i }));

    expect(approveMock).toHaveBeenCalledWith(stableClient, "proposal-1", undefined);
  });

  it("rejecting calls the reject endpoint after confirmation", async () => {
    mockUseAuth.mockReturnValue({ roles: ["RESPONDER"] });
    listMock.mockResolvedValue([proposal]);
    rejectMock.mockResolvedValue({ ...proposal, status: "REJECTED" });
    render(<AgentProposals />);

    await screen.findByText("ACKNOWLEDGE");
    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: /^reject$/i }));

    const dialog = screen.getByRole("alertdialog");
    await user.click(within(dialog).getByRole("button", { name: /^reject$/i }));

    expect(rejectMock).toHaveBeenCalled();
  });
});
