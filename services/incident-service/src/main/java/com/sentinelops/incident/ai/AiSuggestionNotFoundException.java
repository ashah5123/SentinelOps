package com.sentinelops.incident.ai;

import java.util.UUID;

public class AiSuggestionNotFoundException extends RuntimeException {

  private final UUID suggestionId;

  public AiSuggestionNotFoundException(UUID suggestionId) {
    super("AI suggestion not found: " + suggestionId);
    this.suggestionId = suggestionId;
  }

  public UUID suggestionId() {
    return suggestionId;
  }
}
