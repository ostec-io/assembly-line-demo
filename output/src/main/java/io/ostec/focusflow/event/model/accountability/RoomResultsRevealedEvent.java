package io.ostec.focusflow.event.model.accountability;

import java.time.Instant;

public record RoomResultsRevealedEvent(
    String eventId,
    Instant occurredAt,
    String tenantId,
    String orgId,
    String roomId,
    String hostUserId,
    int participantCount,
    int submittedResultCount
) {
}
