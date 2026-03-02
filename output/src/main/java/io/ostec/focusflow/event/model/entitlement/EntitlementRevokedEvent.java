package io.ostec.focusflow.event.model.entitlement;

import java.time.Instant;

public record EntitlementRevokedEvent(
    String eventId,
    Instant occurredAt,
    String tenantId,
    String orgId,
    String userId,
    String entitlement,
    String reason,
    String sourceEventId
) {
}
