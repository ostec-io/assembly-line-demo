package io.ostec.focusflow.dto.response.session;

import io.ostec.focusflow.document.session.FocusSessionStatus;

import java.time.Instant;

public record FocusSessionResponse(
    String sessionId,
    String goal,
    Integer durationMinutes,
    FocusSessionStatus status,
    String planId,
    Instant startedAt,
    Instant endedAt,
    Instant createdAt,
    Integer checkInCount,
    Integer interruptionCount,
    String summary,
    Integer deepWorkMinutes
) {
}
