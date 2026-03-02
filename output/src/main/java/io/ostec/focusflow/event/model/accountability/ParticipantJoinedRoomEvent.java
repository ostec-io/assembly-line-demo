package io.ostec.focusflow.event.model.accountability;

import java.time.Instant;

public record ParticipantJoinedRoomEvent(
    String eventId,
    Instant occurredAt,
    String tenantId,
    String orgId,
    String roomId,
    String userId,
    Instant joinedAt,
    int participantCount
) {
}
