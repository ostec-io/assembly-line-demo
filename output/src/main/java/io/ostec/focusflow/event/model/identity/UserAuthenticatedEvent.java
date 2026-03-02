package io.ostec.focusflow.event.model.identity;

import java.time.Instant;

public record UserAuthenticatedEvent(
    String eventId,
    Instant occurredAt,
    String tenantId,
    String orgId,
    String userId,
    String keycloakUserId,
    String email,
    String ipHash,
    String userAgentHash,
    String sourceEventId
) {
}
