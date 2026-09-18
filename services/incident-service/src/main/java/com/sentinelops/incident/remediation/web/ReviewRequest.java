package com.sentinelops.incident.remediation.web;

import jakarta.validation.constraints.Size;

public record ReviewRequest(@Size(max = 1000) String note) {}
