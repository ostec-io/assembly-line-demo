package io.ostec.focusflow.event.model.session;

import java.time.Instant;

public record FocusSessionStartedEvent(
    String eventId,
    Instant occurredAt,
    String tenantId,
    String orgId,
    String userId,
    String sessionId,
    Instant startedAt
) {
}
