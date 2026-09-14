package com.sentinelops.incident.events;

/** Kafka-compatible topic and event-type names used by the incident service. */
public final class EventTypes {

  public static final String TELEMETRY_ANOMALY_V1 = "telemetry.anomaly.v1";
  public static final String INCIDENT_DETECTED_V1 = "incident.detected.v1";
  public static final String AUDIT_EVENT_V1 = "audit.event.v1";
  public static final String INCIDENT_EVIDENCE_CORRELATED_V1 = "incident.evidence.correlated.v1";

  public static final String INCIDENT_DETECTED_V1_DLQ = "incident.detected.v1.dlq";
  public static final String TELEMETRY_ANOMALY_V1_DLQ = "telemetry.anomaly.v1.dlq";
  public static final String INCIDENT_EVIDENCE_CORRELATED_V1_DLQ =
      "incident.evidence.correlated.v1.dlq";

  public static final int INCIDENT_DETECTED_SCHEMA_VERSION = 1;
  public static final int AUDIT_EVENT_SCHEMA_VERSION = 1;

  private EventTypes() {}
}
