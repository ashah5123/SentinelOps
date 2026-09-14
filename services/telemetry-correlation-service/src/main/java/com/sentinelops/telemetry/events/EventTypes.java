package com.sentinelops.telemetry.events;

/** Kafka-compatible topic and event-type names used by the telemetry-correlation service. */
public final class EventTypes {

  // Consumed
  public static final String DEPLOYMENT_CHANGED_V1 = "deployment.changed.v1";
  public static final String SERVICE_DEPENDENCY_CHANGED_V1 = "service.dependency.changed.v1";
  public static final String INCIDENT_DETECTED_V1 = "incident.detected.v1";

  // Published
  public static final String INCIDENT_EVIDENCE_CORRELATED_V1 = "incident.evidence.correlated.v1";

  // Dead-letter topics
  public static final String DEPLOYMENT_CHANGED_V1_DLQ = "deployment.changed.v1.dlq";
  public static final String SERVICE_DEPENDENCY_CHANGED_V1_DLQ =
      "service.dependency.changed.v1.dlq";
  public static final String INCIDENT_EVIDENCE_CORRELATED_V1_DLQ =
      "incident.evidence.correlated.v1.dlq";

  public static final int DEPLOYMENT_CHANGED_SCHEMA_VERSION = 1;
  public static final int SERVICE_DEPENDENCY_CHANGED_SCHEMA_VERSION = 1;
  public static final int INCIDENT_EVIDENCE_CORRELATED_SCHEMA_VERSION = 1;

  private EventTypes() {}
}
