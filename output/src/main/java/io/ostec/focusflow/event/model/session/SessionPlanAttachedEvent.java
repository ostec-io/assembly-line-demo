package io.ostec.focusflow.event.model.session;

import java.time.Instant;

public record SessionPlanAttachedEvent(
    String eventId,
    Instant occurredAt,
    String tenantId,
    String orgId,
    String userId,
    String sessionId,
    String planId,
    Integer taskCount
) {
}
