package io.ostec.focusflow.dto.response.identity;

import java.time.Instant;

public record AuthSessionAuditResponse(
    String eventType,
    Instant issuedAt,
    String ipHash,
    String userAgentHash
) {
}
