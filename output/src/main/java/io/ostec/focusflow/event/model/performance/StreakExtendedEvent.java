package io.ostec.focusflow.event.model.performance;

import java.time.Instant;
import java.time.LocalDate;

public record StreakExtendedEvent(
    String eventId,
    Instant occurredAt,
    String tenantId,
    String orgId,
    String userId,
    LocalDate date,
    Integer currentStreak,
    Integer longestStreak,
    String sourceEventId
) {
}
