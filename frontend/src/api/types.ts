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
