package com.sentinelops.incident.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

@Schema(
    description =
        "Request body for assigning (or, with a null/absent assigneeId, unassigning) an "
            + "incident. The assignee is always a stable actor subject ID, never a display name.")
public record AssignmentRequest(
    @Size(max = 100) @Schema(example = "responder-demo") String assigneeId) {}
