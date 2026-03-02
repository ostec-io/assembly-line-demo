package io.ostec.focusflow.event.model.performance;

import java.time.Instant;
import java.time.LocalDate;

public record StreakBrokenEvent(
    String eventId,
    Instant occurredAt,
    String tenantId,
    String orgId,
    String userId,
    LocalDate date,
    Integer previousStreak,
    Integer currentStreak,
    String reason,
    String sourceEventId
) {
}
