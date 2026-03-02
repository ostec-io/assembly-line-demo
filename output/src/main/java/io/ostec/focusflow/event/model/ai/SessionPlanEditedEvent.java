package io.ostec.focusflow.event.model.ai;

import java.time.Instant;

public record SessionPlanEditedEvent(
    String eventId,
    Instant occurredAt,
    String tenantId,
    String orgId,
    String userId,
    String sessionId,
    String planId,
    Integer previousRevision,
    Integer revision,
    Integer taskCount,
    Integer totalEstimatedMinutes
) {
}
