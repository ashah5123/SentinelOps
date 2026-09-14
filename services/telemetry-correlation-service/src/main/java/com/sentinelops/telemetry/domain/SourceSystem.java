package com.sentinelops.telemetry.domain;

/** The backend or event stream a normalized {@link Evidence} record was ingested from. */
public enum SourceSystem {
  PROMETHEUS,
  LOKI,
  TEMPO,
  DEPLOYMENT_EVENT,
  DEPENDENCY_EVENT
}
