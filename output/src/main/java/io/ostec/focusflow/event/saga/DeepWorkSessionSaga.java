package io.ostec.focusflow.event.saga;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ostec.focusflow.document.ai.SessionPlanArtifactDocument;
import io.ostec.focusflow.event.model.DomainEventEnvelope;
import io.ostec.focusflow.event.publisher.OutboxPublisherService;
import io.ostec.focusflow.service.ai.PlanGenerationService;
import io.ostec.focusflow.service.entitlement.EntitlementDecisionService;
import io.ostec.focusflow.service.session.SessionCommandService;
import io.ostec.foundation.security.tenant.TenantContext;
import io.ostec.foundation.security.tenant.TenantContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;

@Component
public class DeepWorkSessionSaga {

    private static final Logger log = LoggerFactory.getLogger(DeepWorkSessionSaga.class);

    private static final String SAGA_TYPE = "DeepWorkSessionSaga";
    private static final String SAGA_AGGREGATE_TYPE = "DeepWorkSessionSaga";
    private static final String EVENT_VERSION = "1";

    private static final String STATE_CREATED = "Created";
    private static final String STATE_PLANNED_OPTIONAL = "PlannedOptional";
    private static final String STATE_STARTED = "Started";
    private static final String STATE_COMPLETED = "Completed";
    private static final String STATE_EXPIRED = "Expired";
    private static final String STATE_FAILED = "Failed";

    private static final String STEP_SESSION_CREATED = "onSessionCreated";
    private static final String STEP_ENTITLEMENT_CHECKED = "onEntitlementChecked";
    private static final String STEP_PLAN_GENERATED = "onPlanGenerated";
    private static final String STEP_SESSION_COMPLETED = "onSessionCompleted";

    private static final String EVENT_ENTITLEMENT_CHECKED = "DeepWorkEntitlementChecked";
    private static final String EVENT_MANUAL_PLANNING_ENABLED = "DeepWorkManualPlanningEnabled";
    private static final String EVENT_TIMEOUT_ESCALATED = "DeepWorkSagaTimeoutEscalated";
    private static final String EVENT_SCORE_TRIGGER_REQUESTED = "DeepWorkScoreTriggerRequested";
    private static final String EVENT_COMPENSATED = "DeepWorkSessionCompensated";

    private static final String FEATURE_DEEP_WORK = "focus.deep-work.analytics";
    private static final String FEATURE_AI_PLAN = "ai.plan-memory";

    private final SagaStateStore sagaStateStore;
    private final SagaCompensationPolicy sagaCompensationPolicy;
    private final SessionCommandService sessionCommandService;
    private final PlanGenerationService planGenerationService;
    private final EntitlementDecisionService entitlementDecisionService;
    private final OutboxPublisherService outboxPublisherService;
    private final ObjectMapper objectMapper;

    public DeepWorkSessionSaga(
        SagaStateStore sagaStateStore,
        SagaCompensationPolicy sagaCompensationPolicy,
        SessionCommandService sessionCommandService,
        PlanGenerationService planGenerationService,
        EntitlementDecisionService entitlementDecisionService,
        OutboxPublisherService outboxPublisherService,
        ObjectMapper objectMapper
    ) {
        this.sagaStateStore = sagaStateStore;
        this.sagaCompensationPolicy = sagaCompensationPolicy;
        this.sessionCommandService = sessionCommandService;
        this.planGenerationService = planGenerationService;
        this.entitlementDecisionService = entitlementDecisionService;
        this.outboxPublisherService = outboxPublisherService;
        this.objectMapper = objectMapper;
    }

    @EventListener
    public void onDomainEvent(DomainEventEnvelope envelope) {
        String normalizedType = normalizeEventType(envelope.eventType());
        switch (normalizedType) {
            case "FocusSessionCreated" -> onSessionCreated(envelope);
            case "DeepWorkEntitlementChecked" -> onEntitlementChecked(envelope);
            case "SessionPlanGenerated" -> onPlanGenerated(envelope);
            case "FocusSessionCompleted" -> onSessionCompleted(envelope);
            default -> {
                // No-op: unrelated event type.
            }
        }
    }

    @Scheduled(fixedDelayString = "${focusflow.saga.deep-work-timeout-check-ms:30000}")
    public void checkTimeouts() {
        Instant now = Instant.now();
        for (SagaStateRecord record : sagaStateStore.findTimedOut(SAGA_TYPE, now)) {
            String previousState = record.getState();
            if (record.isTerminal()) {
                continue;
            }

            String tenantId = record.getTenantId();
            String orgId = record.getOrgId();
            String userId = record.getAttribute("userId");
            String correlationId = StringUtils.hasText(record.getCorrelationId())
                ? record.getCorrelationId()
                : UUID.randomUUID().toString();

            String timeoutEventId = deterministicEventId(
                correlationId,
                EVENT_TIMEOUT_ESCALATED,
                record.getSagaId() + ":" + previousState
            );
            record.transitionTo(STATE_EXPIRED, correlationId, timeoutEventId, null);
            record.setCompensationReason("Timeout escalated from state " + previousState);

            if (STATE_STARTED.equals(previousState) && hasScope(tenantId, orgId)) {
                try {
                    withTenantContext(tenantId, orgId, userId, correlationId, () -> {
                        try {
                            sessionCommandService.abortSession(record.getSagaId(), "Session expired by saga timeout");
                        } catch (IllegalStateException ignored) {
                            // Session may already be completed/aborted.
                        }
                    });
                } catch (Exception ex) {
                    log.debug("Unable to abort timed-out session {}", record.getSagaId(), ex);
                }
            }

            if (hasScope(tenantId, orgId)) {
                publishSagaEvent(
                    timeoutEventId,
                    record.getSagaId(),
                    EVENT_TIMEOUT_ESCALATED,
                    new DeepWorkTimeoutEscalatedPayload(
                        timeoutEventId,
                        now,
                        tenantId,
                        orgId,
                        userId,
                        record.getSagaId(),
                        previousState,
                        record.getCompensationReason()
                    ),
                    correlationId,
                    record.getLastEventId(),
                    tenantId,
                    orgId,
                    userId
                );
            }
        }
    }

    public void onSessionCreated(DomainEventEnvelope envelope) {
        if (!hasScope(envelope.tenantId(), envelope.orgId())) {
            log.warn("Skipping FocusSessionCreated because tenant/org context is missing");
            return;
        }

        JsonNode payload = parsePayload(envelope.payload());
        String sessionId = extractText(payload, "sessionId");
        String userId = resolveUserId(payload, envelope);
        if (!StringUtils.hasText(sessionId) || !StringUtils.hasText(userId)) {
            log.warn("Skipping FocusSessionCreated because sessionId or userId is missing");
            return;
        }

        String correlationId = resolveCorrelationId(envelope);
        if (!sagaStateStore.markEventHandled(
            SAGA_TYPE,
            sessionId,
            envelope.tenantId(),
            envelope.orgId(),
            envelope.eventId(),
            correlationId,
            STEP_SESSION_CREATED
        )) {
            return;
        }

        SagaStateRecord record = sagaStateStore.getOrCreate(
            SAGA_TYPE,
            sessionId,
            STATE_CREATED,
            envelope.tenantId(),
            envelope.orgId(),
            correlationId
        );
        record.setScope(envelope.tenantId(), envelope.orgId());
        record.putAttribute("userId", userId);
        record.putAttribute("goal", extractText(payload, "goal"));
        record.putAttribute("durationMinutes", extractText(payload, "durationMinutes"));
        record.transitionTo(
            STATE_CREATED,
            correlationId,
            envelope.eventId(),
            sagaCompensationPolicy.deepWorkEntitlementDeadline(Instant.now())
        );

        EntitlementDecision decision;
        try {
            decision = withTenantContext(envelope.tenantId(), envelope.orgId(), userId, correlationId, () ->
                new EntitlementDecision(
                    entitlementDecisionService.checkFeatureAccess(FEATURE_DEEP_WORK, "start-session", sessionId)
                        && entitlementDecisionService.checkDailyUsage(FEATURE_DEEP_WORK),
                    entitlementDecisionService.checkFeatureAccess(FEATURE_AI_PLAN, "generate-plan", sessionId)
                )
            );
        } catch (Exception ex) {
            record.transitionTo(STATE_FAILED, correlationId, envelope.eventId(), null);
            record.setCompensationReason("Entitlement check failed: " + ex.getMessage());
            log.error("Entitlement check failed for session {}", sessionId, ex);
            return;
        }

        boolean aiPlanningEnabled = decision.entitled() && decision.aiPlanningEnabled();
        String reason = decision.entitled() ? "Entitlement granted" : "Entitlement denied or usage limit reached";

        String entitlementEventId = deterministicEventId(correlationId, EVENT_ENTITLEMENT_CHECKED, sessionId);
        DeepWorkEntitlementCheckedPayload checkedPayload = new DeepWorkEntitlementCheckedPayload(
            entitlementEventId,
            Instant.now(),
            envelope.tenantId(),
            envelope.orgId(),
            userId,
            sessionId,
            decision.entitled(),
            aiPlanningEnabled,
            reason,
            envelope.eventId()
        );

        publishSagaEvent(
            entitlementEventId,
            sessionId,
            EVENT_ENTITLEMENT_CHECKED,
            checkedPayload,
            correlationId,
            envelope.eventId(),
            envelope.tenantId(),
            envelope.orgId(),
            userId
        );

        processEntitlementChecked(
            entitlementEventId,
            envelope.tenantId(),
            envelope.orgId(),
            userId,
            sessionId,
            decision.entitled(),
            aiPlanningEnabled,
            reason,
            correlationId
        );
    }

    public void onEntitlementChecked(DomainEventEnvelope envelope) {
        if (!hasScope(envelope.tenantId(), envelope.orgId())) {
            log.warn("Skipping DeepWorkEntitlementChecked because tenant/org context is missing");
            return;
        }

        JsonNode payload = parsePayload(envelope.payload());
        String sessionId = extractText(payload, "sessionId");
        String userId = resolveUserId(payload, envelope);
        if (!StringUtils.hasText(sessionId) || !StringUtils.hasText(userId)) {
            log.warn("Skipping DeepWorkEntitlementChecked because sessionId or userId is missing");
            return;
        }

        boolean entitled = extractBoolean(payload, "entitled", "granted", "allowed");
        boolean aiPlanningEnabled = extractBoolean(payload, "aiPlanningEnabled", "aiEnabled");
        String reason = extractText(payload, "reason", "message");

        processEntitlementChecked(
            envelope.eventId(),
            envelope.tenantId(),
            envelope.orgId(),
            userId,
            sessionId,
            entitled,
            aiPlanningEnabled,
            reason,
            resolveCorrelationId(envelope)
        );
    }

    public void onPlanGenerated(DomainEventEnvelope envelope) {
        if (!hasScope(envelope.tenantId(), envelope.orgId())) {
            log.warn("Skipping SessionPlanGenerated because tenant/org context is missing");
            return;
        }

        JsonNode payload = parsePayload(envelope.payload());
        String sessionId = extractText(payload, "sessionId");
        String userId = resolveUserId(payload, envelope);
        String planId = extractText(payload, "planId");
        Integer taskCount = extractInteger(payload, "taskCount", "task_count");

        if (!StringUtils.hasText(sessionId) || !StringUtils.hasText(userId) || !StringUtils.hasText(planId)) {
            log.warn("Skipping SessionPlanGenerated because sessionId, userId, or planId is missing");
            return;
        }

        String correlationId = resolveCorrelationId(envelope);
        if (!sagaStateStore.markEventHandled(
            SAGA_TYPE,
            sessionId,
            envelope.tenantId(),
            envelope.orgId(),
            envelope.eventId(),
            correlationId,
            STEP_PLAN_GENERATED
        )) {
            return;
        }

        SagaStateRecord record = sagaStateStore.getOrCreate(
            SAGA_TYPE,
            sessionId,
            STATE_CREATED,
            envelope.tenantId(),
            envelope.orgId(),
            correlationId
        );
        record.putAttribute("userId", userId);
        record.transitionTo(
            STATE_PLANNED_OPTIONAL,
            correlationId,
            envelope.eventId(),
            sagaCompensationPolicy.deepWorkPlanningDeadline(Instant.now())
        );

        try {
            withTenantContext(envelope.tenantId(), envelope.orgId(), userId, correlationId, () -> {
                try {
                    sessionCommandService.attachSessionPlan(sessionId, planId, taskCount);
                } catch (IllegalStateException ex) {
                    if (!shouldIgnorePlanAttachConflict(ex)) {
                        throw ex;
                    }
                }
                sessionCommandService.startSession(sessionId);
            });

            record.transitionTo(
                STATE_STARTED,
                correlationId,
                envelope.eventId(),
                sagaCompensationPolicy.deepWorkSessionDeadline(Instant.now())
            );
        } catch (Exception ex) {
            record.transitionTo(STATE_FAILED, correlationId, envelope.eventId(), null);
            record.setCompensationReason("Plan-generated session start failed: " + ex.getMessage());
            log.error("Failed to start session {} after plan generation", sessionId, ex);
        }
    }

    public void onSessionCompleted(DomainEventEnvelope envelope) {
        if (!hasScope(envelope.tenantId(), envelope.orgId())) {
            log.warn("Skipping FocusSessionCompleted because tenant/org context is missing");
            return;
        }

        JsonNode payload = parsePayload(envelope.payload());
        String sessionId = extractText(payload, "sessionId");
        String userId = resolveUserId(payload, envelope);
        if (!StringUtils.hasText(sessionId) || !StringUtils.hasText(userId)) {
            log.warn("Skipping FocusSessionCompleted because sessionId or userId is missing");
            return;
        }

        String correlationId = resolveCorrelationId(envelope);
        if (!sagaStateStore.markEventHandled(
            SAGA_TYPE,
            sessionId,
            envelope.tenantId(),
            envelope.orgId(),
            envelope.eventId(),
            correlationId,
            STEP_SESSION_COMPLETED
        )) {
            return;
        }

        SagaStateRecord record = sagaStateStore.getOrCreate(
            SAGA_TYPE,
            sessionId,
            STATE_CREATED,
            envelope.tenantId(),
            envelope.orgId(),
            correlationId
        );
        record.putAttribute("userId", userId);
        record.transitionTo(STATE_COMPLETED, correlationId, envelope.eventId(), null);
        record.setCompensationReason(null);

        Integer deepWorkMinutes = extractInteger(payload, "deepWorkMinutes", "focusedMinutes");
        String summary = extractText(payload, "summary", "completionSummary");
        String scoreTriggerEventId = deterministicEventId(correlationId, EVENT_SCORE_TRIGGER_REQUESTED, sessionId);

        publishSagaEvent(
            scoreTriggerEventId,
            sessionId,
            EVENT_SCORE_TRIGGER_REQUESTED,
            new DeepWorkScoreTriggerRequestedPayload(
                scoreTriggerEventId,
                Instant.now(),
                envelope.tenantId(),
                envelope.orgId(),
                userId,
                sessionId,
                deepWorkMinutes,
                summary,
                envelope.eventId()
            ),
            correlationId,
            envelope.eventId(),
            envelope.tenantId(),
            envelope.orgId(),
            userId
        );
    }

    private void processEntitlementChecked(
        String eventId,
        String tenantId,
        String orgId,
        String userId,
        String sessionId,
        boolean entitled,
        boolean aiPlanningEnabled,
        String reason,
        String correlationId
    ) {
        if (!sagaStateStore.markEventHandled(
            SAGA_TYPE,
            sessionId,
            tenantId,
            orgId,
            eventId,
            correlationId,
            STEP_ENTITLEMENT_CHECKED
        )) {
            return;
        }

        SagaStateRecord record = sagaStateStore.getOrCreate(
            SAGA_TYPE,
            sessionId,
            STATE_CREATED,
            tenantId,
            orgId,
            correlationId
        );
        record.putAttribute("userId", userId);
        record.putAttribute("aiPlanningEnabled", String.valueOf(aiPlanningEnabled));

        if (!entitled) {
            record.transitionTo(STATE_FAILED, correlationId, eventId, null);
            record.setCompensationReason(StringUtils.hasText(reason) ? reason : "Entitlement denied");

            try {
                withTenantContext(tenantId, orgId, userId, correlationId, () -> {
                    try {
                        sessionCommandService.abortSession(sessionId, record.getCompensationReason());
                    } catch (IllegalStateException ignored) {
                        // Session may not have started yet or may already be terminal.
                    }
                });
            } catch (Exception ex) {
                log.debug("Unable to abort session {} after entitlement denial", sessionId, ex);
            }

            String compensatedEventId = deterministicEventId(correlationId, EVENT_COMPENSATED, sessionId);
            publishSagaEvent(
                compensatedEventId,
                sessionId,
                EVENT_COMPENSATED,
                new DeepWorkCompensatedPayload(
                    compensatedEventId,
                    Instant.now(),
                    tenantId,
                    orgId,
                    userId,
                    sessionId,
                    record.getCompensationReason(),
                    eventId
                ),
                correlationId,
                eventId,
                tenantId,
                orgId,
                userId
            );
            return;
        }

        if (!aiPlanningEnabled) {
            record.transitionTo(
                STATE_PLANNED_OPTIONAL,
                correlationId,
                eventId,
                sagaCompensationPolicy.deepWorkPlanningDeadline(Instant.now())
            );
            record.setCompensationReason("Manual planning mode: AI planning not entitled");

            String manualModeEventId = deterministicEventId(correlationId, EVENT_MANUAL_PLANNING_ENABLED, sessionId);
            publishSagaEvent(
                manualModeEventId,
                sessionId,
                EVENT_MANUAL_PLANNING_ENABLED,
                new DeepWorkManualPlanningEnabledPayload(
                    manualModeEventId,
                    Instant.now(),
                    tenantId,
                    orgId,
                    userId,
                    sessionId,
                    record.getCompensationReason(),
                    eventId
                ),
                correlationId,
                eventId,
                tenantId,
                orgId,
                userId
            );
            return;
        }

        record.transitionTo(
            STATE_PLANNED_OPTIONAL,
            correlationId,
            eventId,
            sagaCompensationPolicy.deepWorkPlanningDeadline(Instant.now())
        );
        record.setCompensationReason(null);

        try {
            SessionPlanArtifactDocument generatedPlan = withTenantContext(tenantId, orgId, userId, correlationId, () ->
                planGenerationService.generatePlan(sessionId, null, null)
            );

            Integer taskCount = generatedPlan.getTaskBlocks() != null ? generatedPlan.getTaskBlocks().size() : null;
            withTenantContext(tenantId, orgId, userId, correlationId, () -> {
                if (StringUtils.hasText(generatedPlan.getPlanId())) {
                    try {
                        sessionCommandService.attachSessionPlan(sessionId, generatedPlan.getPlanId(), taskCount);
                    } catch (IllegalStateException ex) {
                        if (!shouldIgnorePlanAttachConflict(ex)) {
                            throw ex;
                        }
                    }
                }
                sessionCommandService.startSession(sessionId);
            });

            record.transitionTo(
                STATE_STARTED,
                correlationId,
                eventId,
                sagaCompensationPolicy.deepWorkSessionDeadline(Instant.now())
            );
        } catch (Exception ex) {
            if (sagaCompensationPolicy.shouldFallbackToManualPlanning(ex)) {
                record.transitionTo(
                    STATE_PLANNED_OPTIONAL,
                    correlationId,
                    eventId,
                    sagaCompensationPolicy.deepWorkPlanningDeadline(Instant.now())
                );
                record.setCompensationReason(sagaCompensationPolicy.manualPlanningReason(ex));

                String manualModeEventId = deterministicEventId(correlationId, EVENT_MANUAL_PLANNING_ENABLED, sessionId);
                publishSagaEvent(
                    manualModeEventId,
                    sessionId,
                    EVENT_MANUAL_PLANNING_ENABLED,
                    new DeepWorkManualPlanningEnabledPayload(
                        manualModeEventId,
                        Instant.now(),
                        tenantId,
                        orgId,
                        userId,
                        sessionId,
                        record.getCompensationReason(),
                        eventId
                    ),
                    correlationId,
                    eventId,
                    tenantId,
                    orgId,
                    userId
                );
            } else {
                record.transitionTo(STATE_FAILED, correlationId, eventId, null);
                record.setCompensationReason("AI planning failed without fallback: " + ex.getMessage());
                log.error("DeepWorkSessionSaga failed for session {}", sessionId, ex);
            }
        }
    }

    private boolean shouldIgnorePlanAttachConflict(IllegalStateException exception) {
        if (exception == null || !StringUtils.hasText(exception.getMessage())) {
            return false;
        }
        String normalized = exception.getMessage().toLowerCase();
        return normalized.contains("already has")
            || normalized.contains("already active")
            || normalized.contains("already");
    }

    private void publishSagaEvent(
        String eventId,
        String aggregateId,
        String eventType,
        Object payload,
        String correlationId,
        String causationId,
        String tenantId,
        String orgId,
        String userId
    ) {
        withTenantContext(tenantId, orgId, userId, correlationId, () -> outboxPublisherService.enqueueWithEventId(
            eventId,
            SAGA_AGGREGATE_TYPE,
            aggregateId,
            eventType,
            EVENT_VERSION,
            toJson(payload),
            correlationId,
            StringUtils.hasText(causationId) ? causationId : eventId
        ));
    }

    private JsonNode parsePayload(String payload) {
        if (!StringUtils.hasText(payload)) {
            return objectMapper.createObjectNode();
        }

        try {
            return objectMapper.readTree(payload);
        } catch (Exception ex) {
            log.warn("Unable to parse deep-work saga payload", ex);
            return objectMapper.createObjectNode();
        }
    }

    private boolean extractBoolean(JsonNode node, String... keys) {
        String value = extractText(node, keys);
        if (!StringUtils.hasText(value)) {
            return false;
        }
        return "true".equalsIgnoreCase(value.trim()) || "1".equals(value.trim());
    }

    private Integer extractInteger(JsonNode node, String... keys) {
        String value = extractText(node, keys);
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String extractText(JsonNode node, String... keys) {
        if (node == null) {
            return null;
        }

        for (String key : keys) {
            JsonNode value = node.get(key);
            if (value != null && value.isValueNode()) {
                String text = value.asText();
                if (StringUtils.hasText(text)) {
                    return text;
                }
            }
        }

        return null;
    }

    private String resolveUserId(JsonNode payload, DomainEventEnvelope envelope) {
        String payloadUserId = extractText(payload, "userId", "subjectUserId", "ownerUserId", "actorUserId");
        if (StringUtils.hasText(payloadUserId)) {
            return payloadUserId;
        }

        return resolveUserFromProducer(envelope.producer());
    }

    private String resolveUserFromProducer(String producer) {
        if (!StringUtils.hasText(producer) || !producer.contains(":")) {
            return null;
        }

        String[] parts = producer.split(":", 2);
        if (parts.length < 2 || !StringUtils.hasText(parts[1])) {
            return null;
        }

        return parts[1];
    }

    private String resolveCorrelationId(DomainEventEnvelope envelope) {
        if (StringUtils.hasText(envelope.correlationId())) {
            return envelope.correlationId();
        }
        if (StringUtils.hasText(envelope.eventId())) {
            return envelope.eventId();
        }
        return UUID.randomUUID().toString();
    }

    private String normalizeEventType(String rawType) {
        if (!StringUtils.hasText(rawType)) {
            return "";
        }

        String normalizedType = rawType;
        int lastDotIndex = normalizedType.lastIndexOf('.');
        if (lastDotIndex >= 0 && lastDotIndex < normalizedType.length() - 1) {
            normalizedType = normalizedType.substring(lastDotIndex + 1);
        }

        if (normalizedType.endsWith("Event")) {
            normalizedType = normalizedType.substring(0, normalizedType.length() - "Event".length());
        }

        return normalizedType;
    }

    private String deterministicEventId(String correlationId, String eventType, String aggregateKey) {
        String material = correlationId + ":" + eventType + ":" + aggregateKey;
        return UUID.nameUUIDFromBytes(material.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private String toJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to serialize deep-work saga event payload", ex);
        }
    }

    private boolean hasScope(String tenantId, String orgId) {
        return StringUtils.hasText(tenantId) && StringUtils.hasText(orgId);
    }

    private void withTenantContext(
        String tenantId,
        String orgId,
        String userId,
        String correlationId,
        Runnable action
    ) {
        withTenantContext(tenantId, orgId, userId, correlationId, () -> {
            action.run();
            return null;
        });
    }

    private <T> T withTenantContext(
        String tenantId,
        String orgId,
        String userId,
        String correlationId,
        Supplier<T> action
    ) {
        if (!hasScope(tenantId, orgId)) {
            throw new IllegalStateException("Tenant and org context are required");
        }

        TenantContext previous = TenantContextHolder.getContext();
        boolean hadPreviousContext = TenantContextHolder.hasContext();
        String effectiveUserId = StringUtils.hasText(userId) ? userId : "system";
        String effectiveCorrelationId = StringUtils.hasText(correlationId) ? correlationId : UUID.randomUUID().toString();

        try {
            TenantContextHolder.setContext(TenantContext.builder()
                .tenantId(tenantId)
                .orgId(orgId)
                .userId(effectiveUserId)
                .correlationId(effectiveCorrelationId)
                .build());
            return action.get();
        } finally {
            if (hadPreviousContext && previous != null && previous.hasAnyContext()) {
                TenantContextHolder.setContext(previous);
            } else {
                TenantContextHolder.clear();
            }
        }
    }

    private record EntitlementDecision(boolean entitled, boolean aiPlanningEnabled) {
    }

    private record DeepWorkEntitlementCheckedPayload(
        String eventId,
        Instant occurredAt,
        String tenantId,
        String orgId,
        String userId,
        String sessionId,
        boolean entitled,
        boolean aiPlanningEnabled,
        String reason,
        String sourceEventId
    ) {
    }

    private record DeepWorkManualPlanningEnabledPayload(
        String eventId,
        Instant occurredAt,
        String tenantId,
        String orgId,
        String userId,
        String sessionId,
        String reason,
        String sourceEventId
    ) {
    }

    private record DeepWorkCompensatedPayload(
        String eventId,
        Instant occurredAt,
        String tenantId,
        String orgId,
        String userId,
        String sessionId,
        String reason,
        String sourceEventId
    ) {
    }

    private record DeepWorkTimeoutEscalatedPayload(
        String eventId,
        Instant occurredAt,
        String tenantId,
        String orgId,
        String userId,
        String sessionId,
        String previousState,
        String reason
    ) {
    }

    private record DeepWorkScoreTriggerRequestedPayload(
        String eventId,
        Instant occurredAt,
        String tenantId,
        String orgId,
        String userId,
        String sessionId,
        Integer deepWorkMinutes,
        String summary,
        String sourceEventId
    ) {
    }
}
