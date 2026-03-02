package io.ostec.focusflow.service.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ostec.focusflow.document.session.CompletionSummaryEmbedded;
import io.ostec.focusflow.document.session.FocusSessionDocument;
import io.ostec.focusflow.document.session.FocusSessionStatus;
import io.ostec.focusflow.document.session.InterruptionSignalEmbedded;
import io.ostec.focusflow.document.session.MidSessionCheckInEmbedded;
import io.ostec.focusflow.document.session.SessionGoalEmbedded;
import io.ostec.focusflow.event.model.session.FocusSessionAbortedEvent;
import io.ostec.focusflow.event.model.session.FocusSessionCompletedEvent;
import io.ostec.focusflow.event.model.session.FocusSessionCreatedEvent;
import io.ostec.focusflow.event.model.session.FocusSessionInterruptedEvent;
import io.ostec.focusflow.event.model.session.FocusSessionStartedEvent;
import io.ostec.focusflow.event.model.session.MidSessionCheckInRecordedEvent;
import io.ostec.focusflow.event.model.session.SessionPlanAttachedEvent;
import io.ostec.focusflow.event.publisher.OutboxPublisherService;
import io.ostec.focusflow.exception.ApiErrorCode;
import io.ostec.focusflow.metrics.FocusMetricsService;
import io.ostec.focusflow.repository.session.FocusSessionRepository;
import io.ostec.foundation.security.tenant.TenantContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

@Service
public class SessionCommandService {

    private static final String AGGREGATE_TYPE = "FocusSession";
    private static final String EVENT_VERSION = "1";

    private final FocusSessionRepository focusSessionRepository;
    private final SessionPolicyService sessionPolicyService;
    private final OutboxPublisherService outboxPublisherService;
    private final FocusMetricsService focusMetricsService;
    private final ObjectMapper objectMapper;

    public SessionCommandService(
        FocusSessionRepository focusSessionRepository,
        SessionPolicyService sessionPolicyService,
        OutboxPublisherService outboxPublisherService,
        FocusMetricsService focusMetricsService,
        ObjectMapper objectMapper
    ) {
        this.focusSessionRepository = focusSessionRepository;
        this.sessionPolicyService = sessionPolicyService;
        this.outboxPublisherService = outboxPublisherService;
        this.focusMetricsService = focusMetricsService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public FocusSessionDocument createSession(String goal, Integer durationMinutes, String correlationId) {
        TenantScope scope = resolveScope();
        sessionPolicyService.validateSessionCreation(goal, durationMinutes);

        FocusSessionDocument session = new FocusSessionDocument();
        session.setTenantId(scope.tenantId());
        session.setOrgId(scope.orgId());
        session.setUserId(scope.userId());
        session.setGoal(new SessionGoalEmbedded(goal.trim()));
        session.setDurationMinutes(durationMinutes);
        session.setStatus(FocusSessionStatus.CREATED);

        FocusSessionDocument savedSession = focusSessionRepository.save(session);
        Instant occurredAt = Instant.now();
        String resolvedCorrelationId = resolveCorrelationId(correlationId);
        String eventId = deterministicEventId(resolvedCorrelationId, "FocusSessionCreated", savedSession.getId());

        FocusSessionCreatedEvent event = new FocusSessionCreatedEvent(
            eventId,
            occurredAt,
            scope.tenantId(),
            scope.orgId(),
            scope.userId(),
            savedSession.getId(),
            goal.trim(),
            durationMinutes
        );

        publishSessionEvent(
            event.eventId(),
            savedSession.getId(),
            "FocusSessionCreated",
            event,
            resolvedCorrelationId,
            event.eventId()
        );

        focusMetricsService.incrementSessionCreate();
        return savedSession;
    }

    @Transactional
    public FocusSessionDocument attachSessionPlan(String sessionId, String planId, Integer taskCount) {
        TenantScope scope = resolveScope();
        FocusSessionDocument session = loadOwnedSession(sessionId, scope);

        if (StringUtils.hasText(session.getPlanId())) {
            if (session.getPlanId().equals(planId)) {
                return session;
            }
            throw new IllegalStateException("Session already has a different plan attached");
        }

        if (session.getStatus() != FocusSessionStatus.CREATED) {
            throw new IllegalStateException("Session plan can only be attached while session is in CREATED status");
        }

        session.setPlanId(planId);
        FocusSessionDocument savedSession = focusSessionRepository.save(session);

        Instant occurredAt = Instant.now();
        String correlationId = resolveCorrelationId(null);
        String eventId = deterministicEventId(correlationId, "SessionPlanAttached", savedSession.getId());

        SessionPlanAttachedEvent event = new SessionPlanAttachedEvent(
            eventId,
            occurredAt,
            scope.tenantId(),
            scope.orgId(),
            scope.userId(),
            savedSession.getId(),
            planId,
            taskCount
        );

        publishSessionEvent(
            event.eventId(),
            savedSession.getId(),
            "SessionPlanAttached",
            event,
            correlationId,
            event.eventId()
        );

        return savedSession;
    }

    @Transactional
    public FocusSessionDocument startSession(String sessionId) {
        TenantScope scope = resolveScope();
        FocusSessionDocument session = loadOwnedSession(sessionId, scope);

        sessionPolicyService.validateSessionStart(session);
        if (session.getStatus() == FocusSessionStatus.ACTIVE) {
            return session;
        }

        Instant startedAt = Instant.now();
        session.setStatus(FocusSessionStatus.ACTIVE);
        session.setStartedAt(startedAt);

        FocusSessionDocument savedSession = focusSessionRepository.save(session);

        String correlationId = resolveCorrelationId(null);
        String eventId = deterministicEventId(correlationId, "FocusSessionStarted", savedSession.getId());
        FocusSessionStartedEvent event = new FocusSessionStartedEvent(
            eventId,
            startedAt,
            scope.tenantId(),
            scope.orgId(),
            scope.userId(),
            savedSession.getId(),
            startedAt
        );

        publishSessionEvent(
            event.eventId(),
            savedSession.getId(),
            "FocusSessionStarted",
            event,
            correlationId,
            event.eventId()
        );

        return savedSession;
    }

    @Transactional
    public FocusSessionDocument recordCheckIn(String sessionId, String status, String note) {
        TenantScope scope = resolveScope();
        FocusSessionDocument session = loadOwnedSession(sessionId, scope);

        if (session.getStatus() != FocusSessionStatus.ACTIVE) {
            throw new IllegalStateException("Check-ins can only be recorded while a session is ACTIVE");
        }

        Instant checkedAt = Instant.now();
        MidSessionCheckInEmbedded checkIn = new MidSessionCheckInEmbedded(
            checkedAt,
            StringUtils.hasText(note) ? note.trim() : null,
            toFocusScore(status)
        );

        session.getCheckIns().add(checkIn);
        FocusSessionDocument savedSession = focusSessionRepository.save(session);

        String correlationId = resolveCorrelationId(null);
        String eventId = deterministicEventId(correlationId, "MidSessionCheckInRecorded", savedSession.getId() + ":" + checkedAt);
        MidSessionCheckInRecordedEvent event = new MidSessionCheckInRecordedEvent(
            eventId,
            checkedAt,
            scope.tenantId(),
            scope.orgId(),
            scope.userId(),
            savedSession.getId(),
            status,
            note,
            checkedAt
        );

        publishSessionEvent(
            event.eventId(),
            savedSession.getId(),
            "MidSessionCheckInRecorded",
            event,
            correlationId,
            event.eventId()
        );

        return savedSession;
    }

    @Transactional
    public FocusSessionDocument recordInterruption(String sessionId, String interruptionType, Instant detectedAt) {
        TenantScope scope = resolveScope();
        FocusSessionDocument session = loadOwnedSession(sessionId, scope);

        if (session.getStatus() != FocusSessionStatus.ACTIVE) {
            throw new IllegalStateException("Interruptions can only be recorded while a session is ACTIVE");
        }

        Instant effectiveDetectedAt = detectedAt != null ? detectedAt : Instant.now();
        InterruptionSignalEmbedded interruption = new InterruptionSignalEmbedded(
            effectiveDetectedAt,
            interruptionType,
            null
        );

        session.getInterruptions().add(interruption);
        FocusSessionDocument savedSession = focusSessionRepository.save(session);

        String correlationId = resolveCorrelationId(null);
        String eventId = deterministicEventId(correlationId, "FocusSessionInterrupted", savedSession.getId() + ":" + effectiveDetectedAt);
        FocusSessionInterruptedEvent event = new FocusSessionInterruptedEvent(
            eventId,
            effectiveDetectedAt,
            scope.tenantId(),
            scope.orgId(),
            scope.userId(),
            savedSession.getId(),
            interruptionType,
            effectiveDetectedAt
        );

        publishSessionEvent(
            event.eventId(),
            savedSession.getId(),
            "FocusSessionInterrupted",
            event,
            correlationId,
            event.eventId()
        );

        return savedSession;
    }

    @Transactional
    public FocusSessionDocument completeSession(String sessionId, String summary, Integer deepWorkMinutes) {
        TenantScope scope = resolveScope();
        FocusSessionDocument session = loadOwnedSession(sessionId, scope);

        if (session.getStatus() == FocusSessionStatus.COMPLETED) {
            return session;
        }

        if (session.getStatus() == FocusSessionStatus.ABORTED) {
            throw new IllegalStateException("Aborted sessions cannot be completed");
        }

        SessionPolicyService.CompletionEligibility eligibility = sessionPolicyService.assessCompletionEligibility(session, summary);
        if (!eligibility.eligible()) {
            throw new IllegalStateException(ApiErrorCode.COMPLETION_CRITERIA_NOT_MET.name() + ": " + eligibility.reason());
        }

        Instant endedAt = Instant.now();
        session.setStatus(FocusSessionStatus.COMPLETED);
        session.setEndedAt(endedAt);
        session.setCompletionSummary(new CompletionSummaryEmbedded(
            summary.trim(),
            deepWorkMinutes != null ? "DEEP_WORK_MINUTES:" + deepWorkMinutes : "COMPLETED",
            null,
            endedAt
        ));

        FocusSessionDocument savedSession = focusSessionRepository.save(session);

        String correlationId = resolveCorrelationId(null);
        String eventId = deterministicEventId(correlationId, "FocusSessionCompleted", savedSession.getId());
        FocusSessionCompletedEvent event = new FocusSessionCompletedEvent(
            eventId,
            endedAt,
            scope.tenantId(),
            scope.orgId(),
            scope.userId(),
            savedSession.getId(),
            summary.trim(),
            deepWorkMinutes,
            endedAt
        );

        publishSessionEvent(
            event.eventId(),
            savedSession.getId(),
            "FocusSessionCompleted",
            event,
            correlationId,
            event.eventId()
        );

        focusMetricsService.incrementSessionComplete();
        return savedSession;
    }

    @Transactional
    public FocusSessionDocument abortSession(String sessionId, String reason) {
        TenantScope scope = resolveScope();
        FocusSessionDocument session = loadOwnedSession(sessionId, scope);

        if (session.getStatus() == FocusSessionStatus.ABORTED) {
            return session;
        }

        if (session.getStatus() == FocusSessionStatus.COMPLETED) {
            throw new IllegalStateException("Completed sessions cannot be aborted");
        }

        Instant endedAt = Instant.now();
        session.setStatus(FocusSessionStatus.ABORTED);
        session.setEndedAt(endedAt);

        FocusSessionDocument savedSession = focusSessionRepository.save(session);

        String correlationId = resolveCorrelationId(null);
        String eventId = deterministicEventId(correlationId, "FocusSessionAborted", savedSession.getId());
        FocusSessionAbortedEvent event = new FocusSessionAbortedEvent(
            eventId,
            endedAt,
            scope.tenantId(),
            scope.orgId(),
            scope.userId(),
            savedSession.getId(),
            reason,
            endedAt
        );

        publishSessionEvent(
            event.eventId(),
            savedSession.getId(),
            "FocusSessionAborted",
            event,
            correlationId,
            event.eventId()
        );

        return savedSession;
    }

    private FocusSessionDocument loadOwnedSession(String sessionId, TenantScope scope) {
        FocusSessionDocument session = focusSessionRepository
            .findByIdAndTenantIdAndOrgId(sessionId, scope.tenantId(), scope.orgId())
            .orElseThrow(() -> new IllegalStateException("Session not found for current tenant/org scope"));

        if (!scope.userId().equals(session.getUserId())) {
            throw new IllegalStateException(ApiErrorCode.NOT_HOST.name());
        }

        return session;
    }

    private void publishSessionEvent(
        String eventId,
        String sessionId,
        String eventType,
        Object payload,
        String correlationId,
        String causationId
    ) {
        outboxPublisherService.enqueueWithEventId(
            eventId,
            AGGREGATE_TYPE,
            sessionId,
            eventType,
            EVENT_VERSION,
            toJson(payload),
            correlationId,
            causationId
        );
    }

    private String toJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to serialize session domain event payload", ex);
        }
    }

    private int toFocusScore(String status) {
        if (!StringUtils.hasText(status)) {
            return 3;
        }

        try {
            return Math.clamp(Integer.parseInt(status.trim()), 1, 5);
        } catch (NumberFormatException ignored) {
            String normalized = status.trim().toLowerCase(Locale.ROOT);
            if (normalized.contains("great") || normalized.contains("focus") || normalized.contains("track")) {
                return 5;
            }
            if (normalized.contains("struggle") || normalized.contains("distract") || normalized.contains("blocked")) {
                return 1;
            }
            return 3;
        }
    }

    private String deterministicEventId(String correlationId, String eventType, String sessionKey) {
        String material = correlationId + ":" + eventType + ":" + sessionKey;
        return UUID.nameUUIDFromBytes(material.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private String resolveCorrelationId(String preferredCorrelationId) {
        if (StringUtils.hasText(preferredCorrelationId)) {
            return preferredCorrelationId;
        }

        String correlationFromContext = TenantContextHolder.getCorrelationId();
        if (StringUtils.hasText(correlationFromContext)) {
            return correlationFromContext;
        }

        return UUID.randomUUID().toString();
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
