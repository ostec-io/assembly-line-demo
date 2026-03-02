package io.ostec.focusflow.service.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ostec.focusflow.document.ai.SessionPlanArtifactDocument;
import io.ostec.focusflow.document.ai.SessionPlanStatus;
import io.ostec.focusflow.document.ai.TaskBlockEmbedded;
import io.ostec.focusflow.document.session.FocusSessionDocument;
import io.ostec.focusflow.document.session.FocusSessionStatus;
import io.ostec.focusflow.event.model.ai.SessionPlanAttachedEvent;
import io.ostec.focusflow.event.publisher.OutboxPublisherService;
import io.ostec.focusflow.exception.ApiErrorCode;
import io.ostec.focusflow.repository.ai.SessionPlanRepository;
import io.ostec.focusflow.repository.session.FocusSessionRepository;
import io.ostec.foundation.security.tenant.TenantContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class PlanAttachmentService {

    private static final String AGGREGATE_TYPE = "SessionPlan";
    private static final String EVENT_VERSION = "1";
    private static final int DEFAULT_TOLERANCE_MINUTES = 10;

    private final SessionPlanRepository sessionPlanRepository;
    private final FocusSessionRepository focusSessionRepository;
    private final OutboxPublisherService outboxPublisherService;
    private final ObjectMapper objectMapper;

    public PlanAttachmentService(
        SessionPlanRepository sessionPlanRepository,
        FocusSessionRepository focusSessionRepository,
        OutboxPublisherService outboxPublisherService,
        ObjectMapper objectMapper
    ) {
        this.sessionPlanRepository = sessionPlanRepository;
        this.focusSessionRepository = focusSessionRepository;
        this.outboxPublisherService = outboxPublisherService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public SessionPlanArtifactDocument attachLatestPlan(String planId, String sessionId, Long revision) {
        TenantScope scope = resolveScope();
        FocusSessionDocument session = loadOwnedSession(sessionId, scope);

        if (session.getStatus() != FocusSessionStatus.CREATED) {
            throw new IllegalStateException("Plan can only be attached while session is in CREATED status");
        }

        SessionPlanArtifactDocument latestPlan = sessionPlanRepository
            .findFirstByTenantIdAndOrgIdAndSessionIdOrderByRevisionDesc(scope.tenantId(), scope.orgId(), sessionId)
            .orElseThrow(() -> new IllegalStateException("No generated plan is available for this session"));

        SessionPlanArtifactDocument targetPlan = StringUtils.hasText(planId)
            ? loadOwnedPlan(planId, scope)
            : latestPlan;

        if (!sessionId.equals(targetPlan.getSessionId())) {
            throw new IllegalStateException("Plan does not belong to the requested session");
        }

        assertLatestRevision(targetPlan, latestPlan);
        assertExpectedRevision(targetPlan, revision);
        assertDurationCompatibility(targetPlan, session);

        String effectivePlanId = resolvePlanId(targetPlan);
        if (StringUtils.hasText(session.getPlanId()) && !session.getPlanId().equals(effectivePlanId)) {
            throw new IllegalStateException("Session already has a different plan attached");
        }

        Instant attachedAt = Instant.now();
        targetPlan.setPlanId(effectivePlanId);
        targetPlan.setStatus(SessionPlanStatus.ATTACHED);
        targetPlan.setAttachedVersion(safeRevision(targetPlan.getRevision()));
        targetPlan.setAttachedAt(attachedAt);
        SessionPlanArtifactDocument savedPlan = sessionPlanRepository.save(targetPlan);

        if (!effectivePlanId.equals(session.getPlanId())) {
            session.setPlanId(effectivePlanId);
            focusSessionRepository.save(session);
        }

        String correlationId = resolveCorrelationId(null);
        String eventId = deterministicEventId(
            correlationId,
            "SessionPlanAttached",
            savedPlan.getPlanId() + ":" + savedPlan.getRevision() + ":" + sessionId
        );

        SessionPlanAttachedEvent event = new SessionPlanAttachedEvent(
            eventId,
            attachedAt,
            scope.tenantId(),
            scope.orgId(),
            scope.userId(),
            sessionId,
            savedPlan.getPlanId(),
            savedPlan.getRevision(),
            savedPlan.getTaskBlocks() != null ? savedPlan.getTaskBlocks().size() : 0,
            session.getDurationMinutes()
        );

        publishPlanEvent(
            event.eventId(),
            savedPlan.getPlanId(),
            "SessionPlanAttached",
            event,
            correlationId,
            event.eventId()
        );

        return savedPlan;
    }

    private void assertLatestRevision(SessionPlanArtifactDocument targetPlan, SessionPlanArtifactDocument latestPlan) {
        String targetPlanId = resolvePlanId(targetPlan);
        String latestPlanId = resolvePlanId(latestPlan);
        int targetRevision = safeRevision(targetPlan.getRevision());
        int latestRevision = safeRevision(latestPlan.getRevision());

        if (!targetPlanId.equals(latestPlanId) || targetRevision < latestRevision) {
            throw new IllegalStateException("Only the latest plan revision can be attached to a session");
        }
    }

    private void assertExpectedRevision(SessionPlanArtifactDocument plan, Long expectedRevision) {
        if (expectedRevision == null) {
            return;
        }

        int currentRevision = safeRevision(plan.getRevision());
        if (expectedRevision.longValue() != currentRevision) {
            throw new IllegalStateException(
                "Revision conflict: expectedRevision=" + expectedRevision + ", currentRevision=" + currentRevision
            );
        }
    }

    private void assertDurationCompatibility(SessionPlanArtifactDocument plan, FocusSessionDocument session) {
        int sessionDurationMinutes = session.getDurationMinutes() != null ? session.getDurationMinutes() : 0;
        if (sessionDurationMinutes <= 0) {
            throw new IllegalStateException("Session duration must be configured before plan attachment");
        }

        int planDurationMinutes = plan.getSessionDurationMinutes() != null
            ? plan.getSessionDurationMinutes()
            : sumEstimatedMinutes(plan.getTaskBlocks());
        if (planDurationMinutes <= 0) {
            throw new IllegalStateException("Plan duration must be configured before attachment");
        }

        int totalEstimatedMinutes = sumEstimatedMinutes(plan.getTaskBlocks());
        int toleranceMinutes = plan.getDurationToleranceMinutes() != null
            ? Math.max(0, plan.getDurationToleranceMinutes())
            : DEFAULT_TOLERANCE_MINUTES;

        if (Math.abs(planDurationMinutes - sessionDurationMinutes) > toleranceMinutes) {
            throw new IllegalStateException(
                "Plan duration is incompatible with session duration: planDurationMinutes=" + planDurationMinutes
                    + ", sessionDurationMinutes=" + sessionDurationMinutes
                    + ", toleranceMinutes=" + toleranceMinutes
            );
        }

        if (Math.abs(totalEstimatedMinutes - sessionDurationMinutes) > toleranceMinutes) {
            throw new IllegalStateException(
                "Plan task estimates are incompatible with session duration: estimatedMinutes=" + totalEstimatedMinutes
                    + ", sessionDurationMinutes=" + sessionDurationMinutes
                    + ", toleranceMinutes=" + toleranceMinutes
            );
        }
    }

    private String resolvePlanId(SessionPlanArtifactDocument plan) {
        if (StringUtils.hasText(plan.getPlanId())) {
            return plan.getPlanId();
        }

        if (StringUtils.hasText(plan.getId())) {
            return plan.getId();
        }

        throw new IllegalStateException("Plan identifier is missing");
    }

    private int safeRevision(Integer revision) {
        return revision != null && revision > 0 ? revision : 0;
    }

    private int sumEstimatedMinutes(List<TaskBlockEmbedded> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return 0;
        }

        return blocks.stream()
            .filter(block -> block != null && block.getEstimateMinutes() != null)
            .mapToInt(TaskBlockEmbedded::getEstimateMinutes)
            .sum();
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

    private SessionPlanArtifactDocument loadOwnedPlan(String planId, TenantScope scope) {
        SessionPlanArtifactDocument plan = sessionPlanRepository
            .findByIdAndTenantIdAndOrgId(planId, scope.tenantId(), scope.orgId())
            .orElseThrow(() -> new IllegalStateException("Plan not found for current tenant/org scope"));

        if (!scope.userId().equals(plan.getUserId())) {
            throw new IllegalStateException(ApiErrorCode.NOT_HOST.name());
        }

        return plan;
    }

    private void publishPlanEvent(
        String eventId,
        String aggregateId,
        String eventType,
        Object payload,
        String correlationId,
        String causationId
    ) {
        outboxPublisherService.enqueueWithEventId(
            eventId,
            AGGREGATE_TYPE,
            aggregateId,
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
            throw new IllegalStateException("Unable to serialize AI plan attachment event payload", ex);
        }
    }

    private String deterministicEventId(String correlationId, String eventType, String aggregateKey) {
        String material = correlationId + ":" + eventType + ":" + aggregateKey;
        return UUID.nameUUIDFromBytes(material.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private String resolveCorrelationId(String preferredCorrelationId) {
        if (StringUtils.hasText(preferredCorrelationId)) {
            return preferredCorrelationId;
        }

        String fromContext = TenantContextHolder.getCorrelationId();
        if (StringUtils.hasText(fromContext)) {
            return fromContext;
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
