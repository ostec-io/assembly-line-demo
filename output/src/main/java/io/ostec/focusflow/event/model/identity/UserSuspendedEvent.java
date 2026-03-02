package io.ostec.focusflow.event.model.identity;

import java.time.Instant;

public record UserSuspendedEvent(
    String eventId,
    Instant occurredAt,
    String tenantId,
    String orgId,
    String userId,
    String keycloakUserId,
    String reason,
    String sourceEventId
) {
}
