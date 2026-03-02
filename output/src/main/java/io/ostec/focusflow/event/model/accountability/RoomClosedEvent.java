package io.ostec.focusflow.event.model.accountability;

import java.time.Instant;

public record RoomClosedEvent(
    String eventId,
    Instant occurredAt,
    String tenantId,
    String orgId,
    String roomId,
    String hostUserId,
    Instant closedAt
) {
}
