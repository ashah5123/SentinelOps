package com.sentinelops.telemetry.domain;

/** Lifecycle status of a deployment, as reported by {@code deployment.changed.v1}. */
public enum DeploymentStatus {
  STARTED,
  SUCCEEDED,
  FAILED,
  ROLLED_BACK
}
