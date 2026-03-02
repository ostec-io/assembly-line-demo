package io.ostec.focusflow.event.model.session;

import java.time.Instant;

public record FocusSessionInterruptedEvent(
    String eventId,
    Instant occurredAt,
    String tenantId,
    String orgId,
    String userId,
    String sessionId,
    String interruptionType,
    Instant detectedAt
) {
}
