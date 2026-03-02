package io.ostec.focusflow.event.model.ai;

import java.time.Instant;

public record PlanLearningMemoryUpdatedEvent(
    String eventId,
    Instant occurredAt,
    String tenantId,
    String orgId,
    String userId,
    String memoryId,
    Long memoryVersion,
    String lastPlanId,
    String lastSessionId,
    String feedback,
    String outcome
) {
}
