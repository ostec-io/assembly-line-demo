package io.ostec.focusflow.event.model.accountability;

import java.time.Instant;

public record ParticipantResultSubmittedEvent(
    String eventId,
    Instant occurredAt,
    String tenantId,
    String orgId,
    String roomId,
    String userId,
    String summary,
    Integer deepWorkMinutes,
    Instant submittedAt
) {
}
