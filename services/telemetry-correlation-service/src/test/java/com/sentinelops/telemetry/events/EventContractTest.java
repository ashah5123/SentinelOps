package com.sentinelops.telemetry.events;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import java.io.File;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Contract tests that catch drift between the JSON Schema files in {@code docs/events/schemas/} and
 * this service's actual producer/consumer code: every payload record is serialized with the same
 * {@link ObjectMapper} configuration the application uses, then validated against its documented
 * schema. A field renamed, removed, or retyped in the Java record without updating the schema (or
 * vice versa) fails this test.
 */
class EventContractTest {

  private static final Path SCHEMAS_DIR =
      Path.of("../../docs/events/schemas").toAbsolutePath().normalize();

  private final ObjectMapper objectMapper =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  private final JsonSchemaFactory schemaFactory =
      JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

  @Test
  void schemasDirectoryExists() {
    assertThat(SCHEMAS_DIR).as("docs/events/schemas directory").exists().isDirectory();
  }

  @Test
  void envelopeWrappingADeploymentChangedPayloadValidatesAgainstBothSchemas() throws Exception {
    EventEnvelope<DeploymentChangedPayload> envelope =
        EventEnvelope.of(
            EventTypes.DEPLOYMENT_CHANGED_V1,
            EventTypes.DEPLOYMENT_CHANGED_SCHEMA_VERSION,
            Instant.parse("2026-01-01T00:00:00Z"),
            "corr-1",
            "smoke-test",
            new DeploymentChangedPayload(
                "deploy-1",
                "incident-service",
                "1.2.3",
                "local",
                "SUCCEEDED",
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:01:00Z"),
                "github-actions",
                null));

    assertValidAgainst("event-envelope.schema.json", envelope);
    assertValidAgainst("deployment-changed.v1.schema.json", envelope.payload());
  }

  @Test
  void deploymentChangedPayloadMissingRequiredFieldsFailsValidation() throws Exception {
    JsonNode invalid = objectMapper.readTree("{\"serviceName\":\"incident-service\"}");
    Set<com.networknt.schema.ValidationMessage> errors =
        load("deployment-changed.v1.schema.json").validate(invalid);
    assertThat(errors).isNotEmpty();
  }

  @Test
  void deploymentChangedPayloadWithInvalidServiceNameFailsValidation() throws Exception {
    JsonNode invalid =
        objectMapper.readTree(
            "{\"deploymentId\":\"d1\",\"serviceName\":\"INVALID Service!\",\"version\":\"1.0\","
                + "\"environment\":\"local\",\"status\":\"SUCCEEDED\",\"startedAt\":\"2026-01-01T00:00:00Z\","
                + "\"source\":\"ci\"}");
    Set<com.networknt.schema.ValidationMessage> errors =
        load("deployment-changed.v1.schema.json").validate(invalid);
    assertThat(errors).isNotEmpty();
  }

  @Test
  void serviceDependencyChangedPayloadValidatesAgainstItsSchema() throws Exception {
    ServiceDependencyChangedPayload payload =
        new ServiceDependencyChangedPayload(
            "incident-service",
            "postgres",
            "DATABASE",
            "local",
            "ADDED",
            Instant.parse("2026-01-01T00:00:00Z"));

    assertValidAgainst("service-dependency-changed.v1.schema.json", payload);
  }

  @Test
  void serviceDependencyChangedPayloadWithInvalidOperationFailsValidation() throws Exception {
    JsonNode invalid =
        objectMapper.readTree(
            "{\"sourceService\":\"a\",\"targetService\":\"b\",\"dependencyType\":\"HTTP\","
                + "\"environment\":\"local\",\"operation\":\"NOT_A_REAL_OPERATION\","
                + "\"effectiveAt\":\"2026-01-01T00:00:00Z\"}");
    Set<com.networknt.schema.ValidationMessage> errors =
        load("service-dependency-changed.v1.schema.json").validate(invalid);
    assertThat(errors).isNotEmpty();
  }

  @Test
  void evidenceCorrelatedPayloadValidatesAgainstItsSchema() throws Exception {
    EvidenceCorrelatedPayload payload =
        new EvidenceCorrelatedPayload(
            UUID.randomUUID(),
            UUID.randomUUID(),
            "TRACE",
            "p95 latency elevated",
            "incident-service",
            Instant.parse("2026-01-01T00:00:00Z"),
            "tempo:trace/abc123",
            42.5,
            "affected_service_match(+40); time_proximity(+2.5)",
            "abc123",
            null);

    assertValidAgainst("incident-evidence-correlated.v1.schema.json", payload);
  }

  @Test
  void evidenceCorrelatedPayloadWithNegativeScoreFailsValidation() throws Exception {
    JsonNode invalid =
        objectMapper.readTree(
            "{\"incidentId\":\""
                + UUID.randomUUID()
                + "\",\"evidenceId\":\""
                + UUID.randomUUID()
                + "\","
                + "\"evidenceType\":\"METRIC\",\"summary\":\"s\",\"sourceService\":\"svc\","
                + "\"observedAt\":\"2026-01-01T00:00:00Z\",\"sourceReference\":\"ref\","
                + "\"correlationScore\":-5,\"scoreExplanation\":\"\"}");
    Set<com.networknt.schema.ValidationMessage> errors =
        load("incident-evidence-correlated.v1.schema.json").validate(invalid);
    assertThat(errors).isNotEmpty();
  }

  private void assertValidAgainst(String schemaFileName, Object javaObject) throws Exception {
    JsonNode instance = objectMapper.valueToTree(javaObject);
    Set<com.networknt.schema.ValidationMessage> errors = load(schemaFileName).validate(instance);
    assertThat(errors)
        .as("validation errors for %s against %s: %s", javaObject, schemaFileName, errors)
        .isEmpty();
  }

  private JsonSchema load(String schemaFileName) throws Exception {
    File schemaFile = SCHEMAS_DIR.resolve(schemaFileName).toFile();
    assertThat(schemaFile).as("schema file %s", schemaFileName).exists();
    JsonNode schemaNode = objectMapper.readTree(schemaFile);
    SchemaValidatorsConfig config = SchemaValidatorsConfig.builder().build();
    return schemaFactory.getSchema(schemaNode, config);
  }
}
