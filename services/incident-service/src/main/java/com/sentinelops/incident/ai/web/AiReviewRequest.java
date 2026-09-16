package com.sentinelops.incident.ai.web;

import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * {@code acceptedFields} is a subset of {"severity", "category"} — an empty list means the
 * suggestion is rejected outright. See {@code AiSuggestionReviewService} for what each accepted
 * field actually does.
 */
public record AiReviewRequest(List<String> acceptedFields, @Size(max = 1000) String feedback) {}
