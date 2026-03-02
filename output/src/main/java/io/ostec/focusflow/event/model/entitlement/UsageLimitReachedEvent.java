package io.ostec.focusflow.event.model.entitlement;

import java.time.Instant;
import java.time.LocalDate;

public record UsageLimitReachedEvent(
    String eventId,
    Instant occurredAt,
    String tenantId,
    String orgId,
    String userId,
    String featureKey,
    LocalDate usageDate,
    Integer usageCount,
    Integer dailyLimit,
    String reason
) {
}
