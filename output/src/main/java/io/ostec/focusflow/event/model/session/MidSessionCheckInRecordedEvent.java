package io.ostec.focusflow.event.model.session;

import java.time.Instant;

public record MidSessionCheckInRecordedEvent(
    String eventId,
    Instant occurredAt,
    String tenantId,
    String orgId,
    String userId,
    String sessionId,
    String status,
    String note,
    Instant checkedAt
) {
}
