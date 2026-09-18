// Mirrors services/incident-service's actual DTOs exactly (see docs/api/incident-service.md) —
// nothing here is invented; every field corresponds to a real backend response field.

export type IncidentSeverity = "SEV1" | "SEV2" | "SEV3" | "SEV4";

export type IncidentStatus =
  | "DETECTED"
  | "INVESTIGATING"
  | "AWAITING_APPROVAL"
  | "MITIGATING"
  | "RESOLVED"
  | "FAILED";

export const INCIDENT_STATUSES: IncidentStatus[] = [
  "DETECTED",
  "INVESTIGATING",
  "AWAITING_APPROVAL",
  "MITIGATING",
  "RESOLVED",
  "FAILED",
];

export const INCIDENT_SEVERITIES: IncidentSeverity[] = ["SEV1", "SEV2", "SEV3", "SEV4"];

/** Mirrors IncidentTransitions.java exactly — never invent a transition the backend doesn't allow. */
export const ALLOWED_TRANSITIONS: Record<IncidentStatus, IncidentStatus[]> = {
  DETECTED: ["INVESTIGATING", "FAILED"],
  INVESTIGATING: ["AWAITING_APPROVAL", "MITIGATING", "FAILED"],
  AWAITING_APPROVAL: ["MITIGATING", "INVESTIGATING", "FAILED"],
  MITIGATING: ["RESOLVED", "FAILED"],
  RESOLVED: [],
  FAILED: [],
};

export interface Incident {
  id: string;
  incidentNumber: string;
  title: string;
  description: string | null;
  severity: IncidentSeverity;
  status: IncidentStatus;
  source: string;
  affectedService: string;
  detectedAt: string;
  createdAt: string;
  updatedAt: string;
  resolvedAt: string | null;
  assigneeId: string | null;
  correlationId: string;
  version: number;
}

export interface PageResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  last: boolean;
}

export interface IncidentSummary {
  total: number;
  open: number;
  unacknowledged: number;
  bySeverity: Record<string, number>;
  byStatus: Record<string, number>;
}

export interface TimelineEntry {
  type: "STATUS_TRANSITION" | "EVIDENCE";
  occurredAt: string;
  summary: string;
  correlationId: string;
}

export interface AuditEvent {
  id: string;
  incidentId: string | null;
  action: string;
  actorType: string;
  actorId: string;
  correlationId: string;
  occurredAt: string;
}

export interface DeadLetterReplayResult {
  dlqTopic: string;
  targetTopic: string;
  replayedCount: number;
  failedCount: number;
}

export interface ProblemDetail {
  type?: string;
  title?: string;
  status: number;
  detail?: string;
  instance?: string;
  errorCode?: string;
  timestamp?: string;
  fieldErrors?: { field: string; message: string }[];
  from?: string;
  to?: string;
}

// Phase 11: AI-assisted triage. Mirrors incidents.ai_suggestions / AiSuggestionResponse exactly —
// never authoritative, never mutates an incident, see docs/development/ai-triage.md.
export type AiSuggestionStatus = "COMPLETED" | "FAILED" | "MODEL_UNAVAILABLE";
export type AiReviewStatus = "PENDING" | "ACCEPTED" | "REJECTED" | "PARTIAL";

export interface AiCitation {
  chunkId: string;
  sourceTitle: string;
  section: string;
  score: number;
}

/** The parsed form of AiSuggestion.structuredResult (only present when status is COMPLETED). */
export interface AiTriageResult {
  summary: string;
  suggestedCategory: string;
  suggestedSeverity: IncidentSeverity;
  confidenceStatement: string;
  evidence: string[];
  diagnosticSteps: string[];
  escalationConditions: string[];
  citations: AiCitation[];
  limitations: string[];
}

export interface AiSuggestion {
  id: string;
  incidentId: string;
  providerName: string;
  modelName: string;
  status: AiSuggestionStatus;
  structuredResult: string | null;
  suggestedSeverity: string | null;
  suggestedCategory: string | null;
  failureReason: string | null;
  createdAt: string;
  reviewStatus: AiReviewStatus;
  reviewedBy: string | null;
  reviewedAt: string | null;
  acceptedFields: string | null;
  reviewFeedback: string | null;
}

// Phase 12: external alert ingestion, deduplication, correlation, and notification routing.
// Mirrors AlertEventResponse / AlertCorrelationResponse / NotificationResponse /
// EscalationResponse / ConnectorHealthResponse exactly — see docs/development/alert-ingestion.md.
export interface AlertEvent {
  id: string;
  connectorType: string;
  source: string;
  fingerprint: string;
  fingerprintVersion: number;
  status: "FIRING" | "RESOLVED";
  alertName: string;
  summary: string | null;
  severity: string | null;
  service: string | null;
  environment: string | null;
  region: string | null;
  sourceTimestamp: string;
  ingestedAt: string;
  rawPayloadHash: string;
}

export interface AlertCorrelation {
  id: string;
  alertEventId: string;
  ruleId: string;
  ruleVersion: number;
  matchedFields: string;
  explanation: string;
  correlatedAt: string;
}

export type NotificationStatus = "PENDING" | "SENT" | "FAILED" | "DEAD_LETTERED";

export interface AlertNotification {
  id: string;
  channel: "EMAIL" | "WEBHOOK" | "IN_APP";
  routingRuleId: string;
  routingRuleVersion: number;
  status: NotificationStatus;
  attemptCount: number;
  lastError: string | null;
  createdAt: string;
  sentAt: string | null;
}

export type EscalationStatus = "SCHEDULED" | "DELIVERED" | "CANCELLED";

export interface Escalation {
  id: string;
  routingRuleId: string;
  routingRuleVersion: number;
  scheduledAt: string;
  status: EscalationStatus;
  deliveredAt: string | null;
  cancelledAt: string | null;
  cancelledReason: string | null;
}

export interface ConnectorHealth {
  name: string;
  enabled: boolean;
  lastSuccessfulIngestion: string | null;
  recentFailureCount: number;
  lastSuccessfulNotification: string | null;
  deadLetterCount: number;
  configurationValid: boolean;
}

export interface IncidentQueryParams {
  status?: IncidentStatus;
  severity?: IncidentSeverity;
  affectedService?: string;
  detectedFrom?: string;
  detectedTo?: string;
  assignee?: string;
  unassigned?: boolean;
  page?: number;
  size?: number;
  sort?: string;
}

// Phase 13: MCP-exposed, approval-gated agent operations. Mirrors ProposalResponse exactly — an
// agent (or a responder) may propose an action, but nothing executes until an authorized human
// approves it here in the console. See docs/development/mcp-server.md.
export type ProposalActionType =
  | "ACKNOWLEDGE"
  | "ASSIGN"
  | "CHANGE_SEVERITY"
  | "ADD_NOTE"
  | "ESCALATE"
  | "RESOLVE"
  | "REPLAY_DEAD_LETTER";

export type ProposalStatus =
  | "PENDING"
  | "APPROVED"
  | "REJECTED"
  | "EXPIRED"
  | "EXECUTED"
  | "EXECUTION_FAILED";
export type ProposalRisk = "LOW" | "MEDIUM" | "HIGH";

export interface AgentProposal {
  id: string;
  incidentId: string;
  actionType: ProposalActionType;
  parameters: Record<string, unknown>;
  reason: string;
  evidenceReferences: string[];
  expectedVersion: number;
  riskClassification: ProposalRisk;
  requestedBy: string;
  createdAt: string;
  expiresAt: string;
  status: ProposalStatus;
  approvedBy: string | null;
  approvedAt: string | null;
  reviewNote: string | null;
  rejectedBy: string | null;
  rejectedAt: string | null;
  rejectionNote: string | null;
  executedAt: string | null;
  executionResult: string | null;
  executionError: string | null;
}

export type RemediationExecutionStatus =
  | "PROPOSED"
  | "APPROVED"
  | "SCHEDULED"
  | "RUNNING"
  | "SUCCEEDED"
  | "FAILED"
  | "ROLLED_BACK"
  | "CANCELLED"
  | "DENIED";
export type PolicyOutcome = "ALLOW" | "DENY" | "REQUIRE_APPROVAL";
export type RemediationRisk = "LOW" | "MEDIUM" | "HIGH";
export type RemediationStepStatus = "PENDING" | "RUNNING" | "SUCCEEDED" | "FAILED" | "SKIPPED";

export interface RemediationRunbook {
  id: string;
  slug: string;
  version: number;
  title: string;
  riskClassification: RemediationRisk;
  definitionYaml: string;
  stepCount: number;
  active: boolean;
  createdAt: string;
  createdBy: string;
}

export interface RemediationExecution {
  id: string;
  runbookId: string;
  incidentId: string | null;
  proposalId: string | null;
  status: RemediationExecutionStatus;
  dryRun: boolean;
  requestedBy: string;
  parameters: Record<string, unknown>;
  blastRadius: Record<string, unknown>;
  policyDecision: PolicyOutcome;
  policyReason: string;
  policyVersion: number;
  requiredApprovals: number;
  cancelRequested: boolean;
  emergencyStop: boolean;
  healthBefore: Record<string, unknown> | null;
  healthAfter: Record<string, unknown> | null;
  rollbackReason: string | null;
  failureReason: string | null;
  createdAt: string;
  scheduledAt: string | null;
  startedAt: string | null;
  completedAt: string | null;
  rolledBackAt: string | null;
}

export interface RemediationStep {
  id: string;
  stepIndex: number;
  stepName: string;
  adapterType: string;
  rollbackStep: boolean;
  status: RemediationStepStatus;
  attemptCount: number;
  startedAt: string | null;
  completedAt: string | null;
  output: Record<string, unknown> | null;
  error: string | null;
}

export type BurnRateSeverity = "OK" | "WATCH" | "WARNING" | "CRITICAL" | "UNKNOWN";

export interface SloStatus {
  id: string;
  name: string;
  description: string;
  objective: number;
  windowDays: number;
  known: boolean;
  currentSli: number | null;
  errorBudgetRemaining: number | null;
  burnRate: number | null;
  severity: BurnRateSeverity;
}
