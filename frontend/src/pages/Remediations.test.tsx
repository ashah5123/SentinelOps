import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi, beforeEach } from "vitest";
import { Remediations } from "./Remediations";
import type { RemediationExecution, RemediationRunbook } from "../api/types";

const stableClient = {};
vi.mock("../api/ApiClientProvider", () => ({ useApiClient: () => stableClient }));
vi.mock("../components/Announcer", () => ({ useAnnounce: () => vi.fn() }));

const mockUseAuth = vi.fn();
vi.mock("../auth/AuthProvider", () => ({ useAuth: () => mockUseAuth() }));

const listRunbooksMock = vi.fn();
const listRecentMock = vi.fn();
const getStepsMock = vi.fn();
const proposeMock = vi.fn();
const approveMock = vi.fn();
const rejectMock = vi.fn();
const cancelMock = vi.fn();
const emergencyStopMock = vi.fn();
vi.mock("../api/remediations", () => ({
  listRunbooks: (...args: unknown[]) => listRunbooksMock(...args),
  listRecentRemediations: (...args: unknown[]) => listRecentMock(...args),
  getRemediationSteps: (...args: unknown[]) => getStepsMock(...args),
  proposeRemediation: (...args: unknown[]) => proposeMock(...args),
  approveRemediation: (...args: unknown[]) => approveMock(...args),
  rejectRemediation: (...args: unknown[]) => rejectMock(...args),
  cancelRemediation: (...args: unknown[]) => cancelMock(...args),
  emergencyStopRemediation: (...args: unknown[]) => emergencyStopMock(...args),
}));

const runbook: RemediationRunbook = {
  id: "runbook-1",
  slug: "clear-triage-search-cache",
  version: 1,
  title: "Clear stale triage-search cache entries",
  riskClassification: "LOW",
  definitionYaml: "slug: clear-triage-search-cache\n",
  stepCount: 2,
  active: true,
  createdAt: "2026-01-01T00:00:00Z",
  createdBy: "system",
};

const execution: RemediationExecution = {
  id: "execution-1",
  runbookId: "runbook-1",
  incidentId: null,
  proposalId: null,
  status: "PROPOSED",
  dryRun: false,
  requestedBy: "responder-1",
  parameters: {},
  blastRadius: {},
  policyDecision: "REQUIRE_APPROVAL",
  policyReason: "medium-risk action requires approval",
  policyVersion: 1,
  requiredApprovals: 1,
  cancelRequested: false,
  emergencyStop: false,
  healthBefore: null,
  healthAfter: null,
  rollbackReason: null,
  failureReason: null,
  createdAt: "2026-01-01T00:00:00Z",
  scheduledAt: null,
  startedAt: null,
  completedAt: null,
  rolledBackAt: null,
};

describe("Remediations", () => {
  beforeEach(() => {
    listRunbooksMock.mockReset();
    listRecentMock.mockReset();
    getStepsMock.mockReset();
    proposeMock.mockReset();
    approveMock.mockReset();
    rejectMock.mockReset();
    cancelMock.mockReset();
    emergencyStopMock.mockReset();
    listRunbooksMock.mockResolvedValue([runbook]);
  });

  it("hides review and propose controls for a read-only viewer", async () => {
    mockUseAuth.mockReturnValue({ roles: ["VIEWER"] });
    listRecentMock.mockResolvedValue([execution]);
    render(<Remediations />);

    await screen.findByText("PROPOSED");
    expect(screen.queryByRole("button", { name: /propose remediation/i })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /^approve$/i })).not.toBeInTheDocument();
  });

  it("requires confirmation before approving, then calls the approve endpoint", async () => {
    mockUseAuth.mockReturnValue({ roles: ["RESPONDER"] });
    listRecentMock.mockResolvedValue([execution]);
    approveMock.mockResolvedValue({ ...execution, status: "SCHEDULED" });
    render(<Remediations />);

    await screen.findByText("PROPOSED");
    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: /^approve$/i }));

    expect(approveMock).not.toHaveBeenCalled();

    const dialog = screen.getByRole("alertdialog");
    await user.click(within(dialog).getByRole("button", { name: /^approve$/i }));

    expect(approveMock).toHaveBeenCalledWith(stableClient, "execution-1");
  });

  it("emergency-stop control is only shown to admins on a running execution", async () => {
    mockUseAuth.mockReturnValue({ roles: ["RESPONDER"] });
    listRecentMock.mockResolvedValue([{ ...execution, status: "RUNNING" }]);
    render(<Remediations />);

    await screen.findByText("RUNNING");
    expect(screen.queryByRole("button", { name: /emergency stop/i })).not.toBeInTheDocument();
  });

  it("proposing a remediation submits the selected runbook slug", async () => {
    mockUseAuth.mockReturnValue({ roles: ["RESPONDER"] });
    listRecentMock.mockResolvedValue([]);
    proposeMock.mockResolvedValue({ ...execution, status: "SCHEDULED" });
    render(<Remediations />);

    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: /propose remediation/i }));
    await screen.findByLabelText(/runbook/i);
    await user.selectOptions(screen.getByLabelText(/runbook/i), "clear-triage-search-cache");
    await user.click(screen.getByRole("button", { name: /^submit$/i }));

    expect(proposeMock).toHaveBeenCalledWith(
      stableClient,
      expect.objectContaining({ runbookSlug: "clear-triage-search-cache", dryRun: false }),
    );
  });
});
