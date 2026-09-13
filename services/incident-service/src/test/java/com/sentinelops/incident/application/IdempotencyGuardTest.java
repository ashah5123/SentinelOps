package com.sentinelops.incident.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelops.incident.domain.IdempotentRequest;
import com.sentinelops.incident.infrastructure.persistence.IdempotentRequestRepository;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class IdempotencyGuardTest {

  private record Payload(String value) {}

  private record Response(String result) {}

  @Mock private IdempotentRequestRepository repository;

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void firstRequestExecutesActionAndStoresResult() {
    when(repository.findByIdempotencyKey("key-1")).thenReturn(Optional.empty());
    IdempotencyGuard guard = new IdempotencyGuard(repository, objectMapper);
    AtomicInteger callCount = new AtomicInteger();

    IdempotencyGuard.Outcome<Response> outcome =
        guard.execute(
            "key-1",
            new Payload("a"),
            201,
            () -> {
              callCount.incrementAndGet();
              return new Response("created");
            },
            Response.class);

    assertThat(callCount.get()).isEqualTo(1);
    assertThat(outcome.replayed()).isFalse();
    assertThat(outcome.status()).isEqualTo(201);
    verify(repository).save(any(IdempotentRequest.class));
  }

  @Test
  void repeatingSameKeyAndPayloadReturnsStoredResponseWithoutReexecuting() throws Exception {
    String storedHash = hashOf(new Payload("a"));
    IdempotentRequest existing =
        IdempotentRequest.record(
            "key-1", storedHash, 201, objectMapper.writeValueAsString(new Response("created")));
    when(repository.findByIdempotencyKey("key-1")).thenReturn(Optional.of(existing));
    IdempotencyGuard guard = new IdempotencyGuard(repository, objectMapper);
    AtomicInteger callCount = new AtomicInteger();

    IdempotencyGuard.Outcome<Response> outcome =
        guard.execute(
            "key-1",
            new Payload("a"),
            201,
            () -> {
              callCount.incrementAndGet();
              return new Response("created");
            },
            Response.class);

    assertThat(callCount.get()).isZero();
    assertThat(outcome.replayed()).isTrue();
    assertThat(outcome.body()).isEqualTo(new Response("created"));
    verify(repository, never()).save(any());
  }

  @Test
  void reusingKeyWithDifferentPayloadIsRejected() throws Exception {
    String storedHash = hashOf(new Payload("a"));
    IdempotentRequest existing =
        IdempotentRequest.record(
            "key-1", storedHash, 201, objectMapper.writeValueAsString(new Response("created")));
    when(repository.findByIdempotencyKey("key-1")).thenReturn(Optional.of(existing));
    IdempotencyGuard guard = new IdempotencyGuard(repository, objectMapper);

    assertThatThrownBy(
            () ->
                guard.execute(
                    "key-1",
                    new Payload("different"),
                    201,
                    () -> new Response("created"),
                    Response.class))
        .isInstanceOf(IdempotencyConflictException.class);
  }

  private String hashOf(Payload payload) throws Exception {
    byte[] json = objectMapper.writeValueAsBytes(payload);
    java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
    return java.util.HexFormat.of().formatHex(digest.digest(json));
  }
}
