import type { IncidentQueryParams, IncidentSeverity, IncidentStatus } from "../api/types";
import { INCIDENT_SEVERITIES, INCIDENT_STATUSES } from "../api/types";

/**
 * URL <-> filter serialization for the incident queue, so a filtered view is shareable/
 * refreshable via its URL. Deliberately allowlist-based on both sides: only known, backend-
 * supported fields and enum values ever round-trip — an unrecognized or malformed query
 * parameter is dropped rather than sent to the API or crashing the page.
 */
export interface QueueFilters {
  status?: IncidentStatus;
  severity?: IncidentSeverity;
  affectedService?: string;
  assignee?: string;
  unassigned?: boolean;
  detectedFrom?: string;
  detectedTo?: string;
  page: number;
  size: number;
  sort: string;
}

export const DEFAULT_FILTERS: QueueFilters = {
  page: 0,
  size: 20,
  sort: "detectedAt,desc",
};

/** Explicit allowlist — the only sort expressions the queue UI will ever send. */
export const ALLOWED_SORTS = [
  { value: "detectedAt,desc", label: "Detected (newest first)" },
  { value: "detectedAt,asc", label: "Detected (oldest first)" },
  { value: "severity,asc", label: "Severity (SEV1 first)" },
  { value: "updatedAt,desc", label: "Recently updated" },
] as const;

function isIncidentStatus(value: string): value is IncidentStatus {
  return (INCIDENT_STATUSES as string[]).includes(value);
}

function isIncidentSeverity(value: string): value is IncidentSeverity {
  return (INCIDENT_SEVERITIES as string[]).includes(value);
}

function isAllowedSort(value: string): boolean {
  return ALLOWED_SORTS.some((s) => s.value === value);
}

export function filtersFromSearchParams(params: URLSearchParams): QueueFilters {
  const filters: QueueFilters = { ...DEFAULT_FILTERS };

  const status = params.get("status");
  if (status && isIncidentStatus(status)) {
    filters.status = status;
  }
  const severity = params.get("severity");
  if (severity && isIncidentSeverity(severity)) {
    filters.severity = severity;
  }
  const affectedService = params.get("affectedService");
  if (affectedService && affectedService.trim().length > 0) {
    filters.affectedService = affectedService.trim().slice(0, 100);
  }
  const assignee = params.get("assignee");
  if (assignee && assignee.trim().length > 0) {
    filters.assignee = assignee.trim().slice(0, 100);
  }
  if (params.get("unassigned") === "true") {
    filters.unassigned = true;
  }
  const detectedFrom = params.get("detectedFrom");
  if (detectedFrom && !Number.isNaN(Date.parse(detectedFrom))) {
    filters.detectedFrom = detectedFrom;
  }
  const detectedTo = params.get("detectedTo");
  if (detectedTo && !Number.isNaN(Date.parse(detectedTo))) {
    filters.detectedTo = detectedTo;
  }
  const page = Number.parseInt(params.get("page") ?? "", 10);
  if (Number.isInteger(page) && page >= 0) {
    filters.page = page;
  }
  const size = Number.parseInt(params.get("size") ?? "", 10);
  if (Number.isInteger(size) && size > 0 && size <= 100) {
    filters.size = size;
  }
  const sort = params.get("sort");
  if (sort && isAllowedSort(sort)) {
    filters.sort = sort;
  }

  return filters;
}

export function filtersToSearchParams(filters: QueueFilters): URLSearchParams {
  const params = new URLSearchParams();
  if (filters.status) params.set("status", filters.status);
  if (filters.severity) params.set("severity", filters.severity);
  if (filters.affectedService) params.set("affectedService", filters.affectedService);
  if (filters.assignee) params.set("assignee", filters.assignee);
  if (filters.unassigned) params.set("unassigned", "true");
  if (filters.detectedFrom) params.set("detectedFrom", filters.detectedFrom);
  if (filters.detectedTo) params.set("detectedTo", filters.detectedTo);
  if (filters.page !== DEFAULT_FILTERS.page) params.set("page", String(filters.page));
  if (filters.size !== DEFAULT_FILTERS.size) params.set("size", String(filters.size));
  if (filters.sort !== DEFAULT_FILTERS.sort) params.set("sort", filters.sort);
  return params;
}

export function filtersToQueryParams(filters: QueueFilters): IncidentQueryParams {
  return {
    status: filters.status,
    severity: filters.severity,
    affectedService: filters.affectedService,
    assignee: filters.assignee,
    unassigned: filters.unassigned,
    detectedFrom: filters.detectedFrom,
    detectedTo: filters.detectedTo,
    page: filters.page,
    size: filters.size,
    sort: filters.sort,
  };
}
