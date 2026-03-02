package io.ostec.focusflow.event.model.insights;

import java.time.Instant;

public record AnalyticsEventIngestedEvent(
    String eventId,
    Instant occurredAt,
    String tenantId,
    String orgId,
    String sourceEventId,
    String sourceEventType,
    String sourceContext,
    String userId,
    String factId
) {
}
