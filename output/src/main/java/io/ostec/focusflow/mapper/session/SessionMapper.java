package io.ostec.focusflow.mapper.session;

import io.ostec.focusflow.document.session.CompletionSummaryEmbedded;
import io.ostec.focusflow.document.session.FocusSessionDocument;
import io.ostec.focusflow.dto.response.session.FocusSessionResponse;
import io.ostec.focusflow.dto.response.session.SessionHistoryItemResponse;
import io.ostec.focusflow.dto.response.session.TodaySessionStatusResponse;
import io.ostec.focusflow.service.session.SessionQueryService;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;

@Component
public class SessionMapper {

    public FocusSessionResponse toFocusSessionResponse(FocusSessionDocument session) {
        CompletionSummaryEmbedded completionSummary = session.getCompletionSummary();
        return new FocusSessionResponse(
            session.getId(),
            session.getGoal() != null ? session.getGoal().getText() : null,
            session.getDurationMinutes(),
            session.getStatus(),
            session.getPlanId(),
            session.getStartedAt(),
            session.getEndedAt(),
            session.getCreatedAt(),
            session.getCheckIns() != null ? session.getCheckIns().size() : 0,
            session.getInterruptions() != null ? session.getInterruptions().size() : 0,
            completionSummary != null ? completionSummary.getSummary() : null,
            extractDeepWorkMinutes(completionSummary)
        );
    }

    public TodaySessionStatusResponse toTodaySessionStatusResponse(SessionQueryService.TodayStatus status) {
        return new TodaySessionStatusResponse(
            status.hasActiveSession(),
            status.activeSessionId(),
            status.completedSessions(),
            status.abortedSessions(),
            status.focusedMinutes(),
            status.latestSessionId(),
            status.latestSessionStatus()
        );
    }

    public SessionHistoryItemResponse toSessionHistoryItemResponse(FocusSessionDocument session) {
        return new SessionHistoryItemResponse(
            session.getId(),
            session.getGoal() != null ? session.getGoal().getText() : null,
            session.getDurationMinutes(),
            session.getStatus(),
            session.getPlanId(),
            session.getStartedAt(),
            session.getEndedAt(),
            calculateFocusedMinutes(session),
            session.getCreatedAt()
        );
    }

    private Integer extractDeepWorkMinutes(CompletionSummaryEmbedded completionSummary) {
        if (completionSummary == null || !StringUtils.hasText(completionSummary.getOutcome())) {
            return null;
        }

        String outcome = completionSummary.getOutcome();
        String prefix = "DEEP_WORK_MINUTES:";
        if (!outcome.startsWith(prefix)) {
            return null;
        }

        try {
            return Integer.parseInt(outcome.substring(prefix.length()));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private long calculateFocusedMinutes(FocusSessionDocument session) {
        if (session.getStartedAt() == null || session.getEndedAt() == null) {
            return 0;
        }

        long minutes = Duration.between(session.getStartedAt(), session.getEndedAt()).toMinutes();
        return Math.max(minutes, 0);
    }
}
