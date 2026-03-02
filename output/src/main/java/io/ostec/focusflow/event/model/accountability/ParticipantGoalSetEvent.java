package io.ostec.focusflow.event.model.accountability;

import java.time.Instant;

public record ParticipantGoalSetEvent(
    String eventId,
    Instant occurredAt,
    String tenantId,
    String orgId,
    String roomId,
    String userId,
    String goal,
    Instant setAt
) {
}
