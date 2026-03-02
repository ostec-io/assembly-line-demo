package io.ostec.focusflow.service.session;

import io.ostec.focusflow.document.session.FocusSessionDocument;
import io.ostec.focusflow.document.session.FocusSessionStatus;
import io.ostec.focusflow.exception.ApiErrorCode;
import io.ostec.focusflow.repository.session.FocusSessionRepository;
import io.ostec.foundation.security.tenant.TenantContextHolder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

@Service
public class SessionQueryService {

    private final FocusSessionRepository focusSessionRepository;

    public SessionQueryService(FocusSessionRepository focusSessionRepository) {
        this.focusSessionRepository = focusSessionRepository;
    }

    public TodayStatus getTodayStatus() {
        TenantScope scope = resolveScope();

        Instant now = Instant.now();
        Instant dayStart = LocalDate.now(ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC);

        Page<FocusSessionDocument> todaySessions = focusSessionRepository.findByTenantIdAndOrgIdAndUserIdAndCreatedAtBetween(
            scope.tenantId(),
            scope.orgId(),
            scope.userId(),
            dayStart,
            now,
            PageRequest.of(0, 200, Sort.by(Sort.Direction.DESC, "createdAt"))
        );

        List<FocusSessionDocument> sessions = todaySessions.getContent();
        Optional<FocusSessionDocument> activeSession = focusSessionRepository.findFirstByTenantIdAndOrgIdAndUserIdAndStatus(
            scope.tenantId(),
            scope.orgId(),
            scope.userId(),
            FocusSessionStatus.ACTIVE
        );

        long completedCount = sessions.stream()
            .filter(session -> session.getStatus() == FocusSessionStatus.COMPLETED)
            .count();

        long abortedCount = sessions.stream()
            .filter(session -> session.getStatus() == FocusSessionStatus.ABORTED)
            .count();

        long focusedMinutes = sessions.stream()
            .mapToLong(this::calculateFocusedMinutes)
            .sum();

        FocusSessionDocument latest = sessions.isEmpty() ? null : sessions.getFirst();

        return new TodayStatus(
            scope.tenantId(),
            scope.orgId(),
            scope.userId(),
            activeSession.isPresent(),
            activeSession.map(FocusSessionDocument::getId).orElse(null),
            completedCount,
            abortedCount,
            focusedMinutes,
            latest != null ? latest.getId() : null,
            latest != null ? latest.getStatus() : null
        );
    }

    public Page<FocusSessionDocument> getSessionHistory(Instant from, Instant to, Pageable pageable) {
        TenantScope scope = resolveScope();

        Instant effectiveFrom = from != null ? from : Instant.EPOCH;
        Instant effectiveTo = to != null ? to : Instant.now();

        if (effectiveFrom.isAfter(effectiveTo)) {
            throw new IllegalStateException("History range is invalid: from must be less than or equal to to");
        }

        Pageable effectivePageable = pageable != null
            ? pageable
            : PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"));

        return focusSessionRepository.findByTenantIdAndOrgIdAndUserIdAndCreatedAtBetween(
            scope.tenantId(),
            scope.orgId(),
            scope.userId(),
            effectiveFrom,
            effectiveTo,
            effectivePageable
        );
    }

    public record TodayStatus(
        String tenantId,
        String orgId,
        String userId,
        boolean hasActiveSession,
        String activeSessionId,
        long completedSessions,
        long abortedSessions,
        long focusedMinutes,
        String latestSessionId,
        FocusSessionStatus latestSessionStatus
    ) {
    }

    private long calculateFocusedMinutes(FocusSessionDocument session) {
        if (session.getStartedAt() == null || session.getEndedAt() == null) {
            return 0;
        }

        long minutes = Duration.between(session.getStartedAt(), session.getEndedAt()).toMinutes();
        return Math.max(minutes, 0);
    }

    private TenantScope resolveScope() {
        try {
            return new TenantScope(
                TenantContextHolder.requireTenantId(),
                TenantContextHolder.requireOrgId(),
                TenantContextHolder.requireUserId()
            );
        } catch (IllegalStateException ex) {
            throw new IllegalStateException(ApiErrorCode.TENANT_CONTEXT_MISSING.name(), ex);
        }
    }

    private record TenantScope(String tenantId, String orgId, String userId) {
    }
}
