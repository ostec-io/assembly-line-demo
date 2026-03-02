package io.ostec.focusflow.dto.response.session;

import io.ostec.focusflow.document.session.FocusSessionStatus;

public record TodaySessionStatusResponse(
    boolean hasActiveSession,
    String activeSessionId,
    long completedSessions,
    long abortedSessions,
    long focusedMinutes,
    String latestSessionId,
    FocusSessionStatus latestSessionStatus
) {
}
