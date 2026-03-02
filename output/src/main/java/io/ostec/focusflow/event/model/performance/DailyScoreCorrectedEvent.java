package io.ostec.focusflow.event.model.performance;

import java.time.Instant;
import java.time.LocalDate;

public record DailyScoreCorrectedEvent(
    String eventId,
    Instant occurredAt,
    String tenantId,
    String orgId,
    String userId,
    LocalDate date,
    Integer previousFocusScore,
    Integer correctedFocusScore,
    String reason,
    String correctedByUserId,
    String sourceEventId
) {
}
