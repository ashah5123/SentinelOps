package com.sentinelops.telemetry.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A single normalized piece of telemetry evidence — a metric sample, a log line, a trace span, a
 * deployment, or a dependency change — reduced to one shared, searchable shape.
 *
 * <p>Not every field applies to every {@link EvidenceType}; irrelevant fields are simply left
 * {@code null} rather than forced to a placeholder value (see {@code
 * docs/decisions/0010-incremental-ingestion-and-correlation.md}). {@link #attributes} is the only
 * place bounded, evidence-type-specific supplementary data lives — every field that correlation or
 * the API needs to search, filter, or index on on is a real relational column instead.
 */
@Entity
@Table(name = "evidence", schema = "telemetry")
public class Evidence {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Enumerated(EnumType.STRING)
  @Column(name = "evidence_type", nullable = false, updatable = false, length = 20)
  private EvidenceType evidenceType;

  @Enumerated(EnumType.STRING)
  @Column(name = "source_system", nullable = false, updatable = false, length = 30)
  private SourceSystem sourceSystem;

  @Column(name = "source_service", nullable = false, updatable = false, length = 150)
  private String sourceService;

  @Column(name = "observed_at", nullable = false, updatable = false)
  private Instant observedAt;

  @Column(name = "ingested_at", nullable = false, updatable = false)
  private Instant ingestedAt;

  @Column(name = "trace_id", updatable = false, length = 64)
  private String traceId;

  @Column(name = "span_id", updatable = false, length = 32)
  private String spanId;

  @Column(name = "correlation_id", updatable = false, length = 128)
  private String correlationId;

  @Column(name = "deployment_id", updatable = false)
  private UUID deploymentId;

  @Column(name = "metric_name", updatable = false, length = 200)
  private String metricName;

  @Column(name = "metric_value", updatable = false)
  private Double metricValue;

  @Column(name = "severity", updatable = false, length = 20)
  private String severity;

  @Column(name = "summary", nullable = false, updatable = false, length = 500)
  private String summary;

  @Column(name = "source_reference", nullable = false, updatable = false, length = 500)
  private String sourceReference;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "attributes", updatable = false, columnDefinition = "jsonb")
  private Map<String, String> attributes;

  @Column(name = "fingerprint", nullable = false, updatable = false, unique = true, length = 128)
  private String fingerprint;

  protected Evidence() {
    // required by JPA
  }

  private Evidence(Builder b) {
    this.id = UUID.randomUUID();
    this.evidenceType = b.evidenceType;
    this.sourceSystem = b.sourceSystem;
    this.sourceService = b.sourceService;
    this.observedAt = b.observedAt;
    this.ingestedAt = Instant.now();
    this.traceId = b.traceId;
    this.spanId = b.spanId;
    this.correlationId = b.correlationId;
    this.deploymentId = b.deploymentId;
    this.metricName = b.metricName;
    this.metricValue = b.metricValue;
    this.severity = b.severity;
    this.summary = b.summary;
    this.sourceReference = b.sourceReference;
    this.attributes = b.attributes;
    this.fingerprint = b.fingerprint;
  }

  public static Builder builder(
      EvidenceType evidenceType, SourceSystem sourceSystem, String sourceService) {
    return new Builder(evidenceType, sourceSystem, sourceService);
  }

  public UUID getId() {
    return id;
  }

  public EvidenceType getEvidenceType() {
    return evidenceType;
  }

  public SourceSystem getSourceSystem() {
    return sourceSystem;
  }

  public String getSourceService() {
    return sourceService;
  }

  public Instant getObservedAt() {
    return observedAt;
  }

  public Instant getIngestedAt() {
    return ingestedAt;
  }

  public String getTraceId() {
    return traceId;
  }

  public String getSpanId() {
    return spanId;
  }

  public String getCorrelationId() {
    return correlationId;
  }

  public UUID getDeploymentId() {
    return deploymentId;
  }

  public String getMetricName() {
    return metricName;
  }

  public Double getMetricValue() {
    return metricValue;
  }

  public String getSeverity() {
    return severity;
  }

  public String getSummary() {
    return summary;
  }

  public String getSourceReference() {
    return sourceReference;
  }

  public Map<String, String> getAttributes() {
    return attributes;
  }

  public String getFingerprint() {
    return fingerprint;
  }

  /** Builds an {@link Evidence} record, requiring the three fields every type must have. */
  public static final class Builder {
    private final EvidenceType evidenceType;
    private final SourceSystem sourceSystem;
    private final String sourceService;
    private Instant observedAt;
    private String traceId;
    private String spanId;
    private String correlationId;
    private UUID deploymentId;
    private String metricName;
    private Double metricValue;
    private String severity;
    private String summary;
    private String sourceReference;
    private Map<String, String> attributes;
    private String fingerprint;

    private Builder(EvidenceType evidenceType, SourceSystem sourceSystem, String sourceService) {
      this.evidenceType = evidenceType;
      this.sourceSystem = sourceSystem;
      this.sourceService = sourceService;
    }

    public Builder observedAt(Instant observedAt) {
      this.observedAt = observedAt;
      return this;
    }

    public Builder traceId(String traceId) {
      this.traceId = traceId;
      return this;
    }

    public Builder spanId(String spanId) {
      this.spanId = spanId;
      return this;
    }

    public Builder correlationId(String correlationId) {
      this.correlationId = correlationId;
      return this;
    }

    public Builder deploymentId(UUID deploymentId) {
      this.deploymentId = deploymentId;
      return this;
    }

    public Builder metricName(String metricName) {
      this.metricName = metricName;
      return this;
    }

    public Builder metricValue(Double metricValue) {
      this.metricValue = metricValue;
      return this;
    }

    public Builder severity(String severity) {
      this.severity = severity;
      return this;
    }

    public Builder summary(String summary) {
      this.summary = summary;
      return this;
    }

    public Builder sourceReference(String sourceReference) {
      this.sourceReference = sourceReference;
      return this;
    }

    public Builder attributes(Map<String, String> attributes) {
      this.attributes = attributes;
      return this;
    }

    public Builder fingerprint(String fingerprint) {
      this.fingerprint = fingerprint;
      return this;
    }

    // Read accessors for the fields FingerprintService needs, so callers can compute a
    // fingerprint from a builder's already-set fields before finishing it with fingerprint(...)
    // and build() — avoiding building the same Evidence twice.
    public Instant observedAtValue() {
      return observedAt;
    }

    public String metricNameValue() {
      return metricName;
    }

    public String traceIdValue() {
      return traceId;
    }

    public String spanIdValue() {
      return spanId;
    }

    public Map<String, String> attributesValue() {
      return attributes;
    }

    public String summaryValue() {
      return summary;
    }

    public Evidence build() {
      if (observedAt == null || summary == null || sourceReference == null || fingerprint == null) {
        throw new IllegalStateException(
            "observedAt, summary, sourceReference, and fingerprint are required on every Evidence"
                + " record");
      }
      return new Evidence(this);
    }
  }
}
