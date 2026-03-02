package io.ostec.focusflow.event.model.accountability;

import java.time.Instant;

public record RoomCreatedEvent(
    String eventId,
    Instant occurredAt,
    String tenantId,
    String orgId,
    String roomId,
    String hostUserId,
    String title,
    String visibility,
    Integer capacity
) {
}
