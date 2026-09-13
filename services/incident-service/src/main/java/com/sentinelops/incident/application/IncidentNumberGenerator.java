package com.sentinelops.incident.application;

import com.sentinelops.incident.infrastructure.persistence.IncidentRepository;
import java.time.Clock;
import java.time.Year;
import java.time.ZoneOffset;
import org.springframework.stereotype.Component;

/**
 * Generates human-readable incident numbers of the form {@code INC-<year>-<sequence>}, e.g. {@code
 * INC-2026-000123}. The sequence is scoped per calendar year (UTC).
 *
 * <p>The generated number is a display convenience, not a strict gapless sequence: under concurrent
 * creation, two callers may compute the same candidate number, but the database's unique constraint
 * on {@code incident_number} prevents a collision from being persisted — the caller is expected to
 * retry generation on a constraint violation.
 */
@Component
public class IncidentNumberGenerator {

  private final IncidentRepository incidentRepository;
  private final Clock clock;

  public IncidentNumberGenerator(IncidentRepository incidentRepository, Clock clock) {
    this.incidentRepository = incidentRepository;
    this.clock = clock;
  }

  public String next() {
    int year = Year.now(clock.withZone(ZoneOffset.UTC)).getValue();
    String prefix = "INC-%d-".formatted(year);
    long countThisYear = incidentRepository.countByIncidentNumberStartingWith(prefix);
    long sequence = countThisYear + 1;
    return prefix + "%06d".formatted(sequence);
  }
}
