package io.ostec.focusflow.event.model.session;

import java.time.Instant;

public record FocusSessionCompletedEvent(
    String eventId,
    Instant occurredAt,
    String tenantId,
    String orgId,
    String userId,
    String sessionId,
    String summary,
    Integer deepWorkMinutes,
    Instant endedAt
) {
}
