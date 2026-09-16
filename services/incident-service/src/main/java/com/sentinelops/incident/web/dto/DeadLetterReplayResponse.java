package com.sentinelops.incident.web.dto;

import com.sentinelops.incident.application.DeadLetterReplayService.ReplayResult;

public record DeadLetterReplayResponse(
    String dlqTopic, String targetTopic, int replayedCount, int failedCount) {

  public static DeadLetterReplayResponse from(ReplayResult result) {
    return new DeadLetterReplayResponse(
        result.dlqTopic(), result.targetTopic(), result.replayed(), result.failed());
  }
}
