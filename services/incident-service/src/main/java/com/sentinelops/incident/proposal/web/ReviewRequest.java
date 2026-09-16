package com.sentinelops.incident.proposal.web;

import jakarta.validation.constraints.Size;

public record ReviewRequest(@Size(max = 1000) String reviewNote) {}
