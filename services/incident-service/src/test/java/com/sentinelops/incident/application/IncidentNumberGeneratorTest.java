package com.sentinelops.incident.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.sentinelops.incident.infrastructure.persistence.IncidentRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class IncidentNumberGeneratorTest {

  @Mock private IncidentRepository incidentRepository;

  private final Clock fixedClock =
      Clock.fixed(Instant.parse("2026-09-12T00:00:00Z"), ZoneOffset.UTC);

  @Test
  void generatesFirstNumberOfTheYearWhenNoneExistYet() {
    when(incidentRepository.countByIncidentNumberStartingWith("INC-2026-")).thenReturn(0L);

    IncidentNumberGenerator generator = new IncidentNumberGenerator(incidentRepository, fixedClock);

    assertThat(generator.next()).isEqualTo("INC-2026-000001");
  }

  @Test
  void incrementsSequenceBasedOnExistingCountForTheYear() {
    when(incidentRepository.countByIncidentNumberStartingWith(anyString())).thenReturn(41L);

    IncidentNumberGenerator generator = new IncidentNumberGenerator(incidentRepository, fixedClock);

    assertThat(generator.next()).isEqualTo("INC-2026-000042");
  }

  @Test
  void usesTheYearFromTheProvidedClock() {
    Clock nextYear = Clock.fixed(Instant.parse("2027-01-01T00:00:00Z"), ZoneOffset.UTC);
    when(incidentRepository.countByIncidentNumberStartingWith("INC-2027-")).thenReturn(0L);

    IncidentNumberGenerator generator = new IncidentNumberGenerator(incidentRepository, nextYear);

    assertThat(generator.next()).startsWith("INC-2027-");
  }
}
