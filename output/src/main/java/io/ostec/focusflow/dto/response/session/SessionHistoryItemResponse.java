package io.ostec.focusflow.dto.response.session;

import io.ostec.focusflow.document.session.FocusSessionStatus;

import java.time.Instant;

public record SessionHistoryItemResponse(
    String sessionId,
    String goal,
    Integer durationMinutes,
    FocusSessionStatus status,
    String planId,
    Instant startedAt,
    Instant endedAt,
    long focusedMinutes,
    Instant createdAt
) {
}
