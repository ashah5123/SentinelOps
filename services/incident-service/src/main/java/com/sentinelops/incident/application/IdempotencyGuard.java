package com.sentinelops.incident.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelops.incident.domain.IdempotentRequest;
import com.sentinelops.incident.infrastructure.persistence.IdempotentRequestRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Enforces {@code Idempotency-Key} semantics for unsafe-but-idempotent write requests: a safe
 * repeat of the same request (same key, same payload) returns the original response instead of
 * re-executing the action, while reuse of a key with a materially different payload is rejected.
 *
 * <p>The idempotency record is written in the same database transaction as the action itself, so a
 * crash between the two can never leave one without the other.
 */
@Component
public class IdempotencyGuard {

  private final IdempotentRequestRepository idempotentRequestRepository;
  private final ObjectMapper objectMapper;

  public IdempotencyGuard(
      IdempotentRequestRepository idempotentRequestRepository, ObjectMapper objectMapper) {
    this.idempotentRequestRepository = idempotentRequestRepository;
    this.objectMapper = objectMapper;
  }

  /** Result of an idempotency-guarded operation. */
  public record Outcome<T>(T body, int status, boolean replayed) {}

  @Transactional
  public <RequestT, ResponseT> Outcome<ResponseT> execute(
      String idempotencyKey,
      RequestT requestPayload,
      int successStatus,
      Supplier<ResponseT> action,
      Class<ResponseT> responseType) {
    String requestHash = hash(requestPayload);

    Optional<IdempotentRequest> existing =
        idempotentRequestRepository.findByIdempotencyKey(idempotencyKey);
    if (existing.isPresent()) {
      IdempotentRequest record = existing.get();
      if (!record.getRequestHash().equals(requestHash)) {
        throw new IdempotencyConflictException(idempotencyKey);
      }
      return new Outcome<>(
          readValue(record.getResponseBody(), responseType), record.getResponseStatus(), true);
    }

    ResponseT response = action.get();
    idempotentRequestRepository.save(
        IdempotentRequest.record(idempotencyKey, requestHash, successStatus, writeValue(response)));
    return new Outcome<>(response, successStatus, false);
  }

  private String hash(Object payload) {
    try {
      byte[] canonicalJson = objectMapper.writeValueAsBytes(payload);
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(canonicalJson));
    } catch (JsonProcessingException | NoSuchAlgorithmException e) {
      throw new IllegalStateException("Failed to hash idempotent request payload", e);
    }
  }

  private String writeValue(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Failed to serialize idempotent response payload", e);
    }
  }

  private <T> T readValue(String json, Class<T> type) {
    try {
      return objectMapper.readValue(json.getBytes(StandardCharsets.UTF_8), type);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to deserialize stored idempotent response", e);
    }
  }
}
