package io.ostec.focusflow.event.model.identity;

import java.time.Instant;

public record UserRegisteredEvent(
    String eventId,
    Instant occurredAt,
    String tenantId,
    String orgId,
    String userId,
    String keycloakUserId,
    String email,
    String displayName,
    String sourceEventId
) {
}
