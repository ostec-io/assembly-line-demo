package io.ostec.focusflow.event.model.identity;

import java.time.Instant;

public record AccessTokenIssuedEvent(
    String eventId,
    Instant occurredAt,
    String tenantId,
    String orgId,
    String userId,
    String keycloakUserId,
    String tokenId,
    Instant expiresAt,
    String sourceEventId
) {
}
