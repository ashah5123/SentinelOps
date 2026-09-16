import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi, beforeEach } from "vitest";
import { AiAssistancePanel } from "./AiAssistancePanel";
import type { AiSuggestion } from "../api/types";

const stableClient = {};
vi.mock("../api/ApiClientProvider", () => ({ useApiClient: () => stableClient }));
vi.mock("./Announcer", () => ({ useAnnounce: () => vi.fn() }));

const generateMock = vi.fn();
const listMock = vi.fn();
const reviewMock = vi.fn();
vi.mock("../api/ai", () => ({
  generateAiSuggestion: (...args: unknown[]) => generateMock(...args),
  listAiSuggestions: (...args: unknown[]) => listMock(...args),
  reviewAiSuggestion: (...args: unknown[]) => reviewMock(...args),
}));

const completedSuggestion: AiSuggestion = {
  id: "sugg-1",
  incidentId: "incident-1",
  providerName: "deterministic-rules-v1",
  modelName: "deterministic-rules-v1",
  status: "COMPLETED",
  structuredResult: JSON.stringify({
    summary: "A SEV2 incident affecting checkout-api.",
    suggestedCategory: "Performance",
    suggestedSeverity: "SEV2",
    confidenceStatement: "Low confidence — deterministic fallback.",
    evidence: ["Reported severity: SEV2"],
    diagnosticSteps: ["Review the retrieved runbook passage."],
    escalationConditions: ["Escalate if customer impact worsens."],
    citations: [
      { chunkId: "chunk-1", sourceTitle: "Elevated API Latency", section: "Symptoms", score: 0.9 },
    ],
    limitations: ["Rule-based, not an LLM."],
  }),
  suggestedSeverity: "SEV2",
  suggestedCategory: "Performance",
  failureReason: null,
  createdAt: "2026-09-15T12:00:00Z",
  reviewStatus: "PENDING",
  reviewedBy: null,
  reviewedAt: null,
  acceptedFields: null,
  reviewFeedback: null,
};

describe("AiAssistancePanel", () => {
  beforeEach(() => {
    generateMock.mockReset();
    listMock.mockReset();
    reviewMock.mockReset();
  });

  it("hides the generate button for a read-only viewer but still lists suggestions", async () => {
    listMock.mockResolvedValue([completedSuggestion]);
    render(
      <AiAssistancePanel incidentId="incident-1" canRequest={false} onIncidentChanged={vi.fn()} />,
    );

    expect(
      screen.queryByRole("button", { name: /generate ai triage suggestion/i }),
    ).not.toBeInTheDocument();
    await screen.findByText(/A SEV2 incident affecting checkout-api/);
  });

  it("requests a suggestion and refreshes the list", async () => {
    listMock.mockResolvedValue([]);
    generateMock.mockResolvedValue(completedSuggestion);
    render(
      <AiAssistancePanel incidentId="incident-1" canRequest={true} onIncidentChanged={vi.fn()} />,
    );

    await screen.findByText(/no ai suggestions have been requested/i);
    listMock.mockResolvedValue([completedSuggestion]);

    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: /generate ai triage suggestion/i }));

    await waitFor(() => expect(generateMock).toHaveBeenCalledWith(stableClient, "incident-1"));
    await screen.findByText(/A SEV2 incident affecting checkout-api/);
  });

  it("expanding evidence reveals citations, and accepting severity calls review + onIncidentChanged", async () => {
    listMock.mockResolvedValue([completedSuggestion]);
    reviewMock.mockResolvedValue({
      ...completedSuggestion,
      reviewStatus: "ACCEPTED",
      reviewedBy: "responder-1",
    });
    const onIncidentChanged = vi.fn();
    render(
      <AiAssistancePanel
        incidentId="incident-1"
        canRequest={true}
        onIncidentChanged={onIncidentChanged}
      />,
    );

    await screen.findByText(/A SEV2 incident affecting checkout-api/);
    const user = userEvent.setup();

    expect(screen.queryByText(/Elevated API Latency/)).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: /show evidence and citations/i }));
    expect(screen.getByText(/Elevated API Latency/)).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: /accept suggested severity/i }));

    await waitFor(() =>
      expect(reviewMock).toHaveBeenCalledWith(stableClient, "incident-1", "sugg-1", {
        acceptedFields: ["severity"],
        feedback: undefined,
      }),
    );
    await waitFor(() => expect(onIncidentChanged).toHaveBeenCalled());
  });

  it("rejecting a suggestion never calls onIncidentChanged", async () => {
    listMock.mockResolvedValue([completedSuggestion]);
    reviewMock.mockResolvedValue({ ...completedSuggestion, reviewStatus: "REJECTED" });
    const onIncidentChanged = vi.fn();
    render(
      <AiAssistancePanel
        incidentId="incident-1"
        canRequest={true}
        onIncidentChanged={onIncidentChanged}
      />,
    );

    await screen.findByText(/A SEV2 incident affecting checkout-api/);
    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: /^reject$/i }));

    await waitFor(() =>
      expect(reviewMock).toHaveBeenCalledWith(stableClient, "incident-1", "sugg-1", {
        acceptedFields: [],
        feedback: undefined,
      }),
    );
    expect(onIncidentChanged).not.toHaveBeenCalled();
  });

  it("shows a model-unavailable message without a structured result", async () => {
    listMock.mockResolvedValue([
      {
        ...completedSuggestion,
        status: "MODEL_UNAVAILABLE",
        structuredResult: null,
        failureReason: "circuit open",
      },
    ]);
    render(
      <AiAssistancePanel incidentId="incident-1" canRequest={true} onIncidentChanged={vi.fn()} />,
    );

    await screen.findByText(/temporarily unavailable: circuit open/i);
  });
});
