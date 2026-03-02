package io.ostec.focusflow.service.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ostec.focusflow.document.ai.PlanMemoryDocument;
import io.ostec.focusflow.document.ai.PlanRevisionEmbedded;
import io.ostec.focusflow.document.ai.SessionPlanArtifactDocument;
import io.ostec.focusflow.document.ai.SessionPlanStatus;
import io.ostec.focusflow.document.ai.TaskBlockEmbedded;
import io.ostec.focusflow.document.session.FocusSessionDocument;
import io.ostec.focusflow.document.session.FocusSessionStatus;
import io.ostec.focusflow.event.model.ai.PlanLearningMemoryUpdatedEvent;
import io.ostec.focusflow.event.model.ai.SessionPlanEditedEvent;
import io.ostec.focusflow.event.model.ai.SessionPlanGeneratedEvent;
import io.ostec.focusflow.event.publisher.OutboxPublisherService;
import io.ostec.focusflow.exception.ApiErrorCode;
import io.ostec.focusflow.metrics.FocusMetricsService;
import io.ostec.focusflow.repository.ai.PlanMemoryRepository;
import io.ostec.focusflow.repository.ai.SessionPlanRepository;
import io.ostec.focusflow.repository.session.FocusSessionRepository;
import io.ostec.focusflow.repository.session.SessionPolicyAuditRepository;
import io.ostec.foundation.security.tenant.TenantContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Service
public class PlanGenerationService {

    private static final String AGGREGATE_TYPE = "SessionPlan";
    private static final String EVENT_VERSION = "1";
    private static final String PLAN_MEMORY_POLICY = "plan-memory";
    private static final String ALLOW = "ALLOW";
    private static final String DEFAULT_TEMPLATE_VERSION = "v1";
    private static final String DEFAULT_PROVIDER = "template-fallback";
    private static final int MAX_MEMORY_ITEMS = 40;

    private final SessionPlanRepository sessionPlanRepository;
    private final PlanMemoryRepository planMemoryRepository;
    private final FocusSessionRepository focusSessionRepository;
    private final SessionPolicyAuditRepository sessionPolicyAuditRepository;
    private final AiProviderAclService aiProviderAclService;
    private final OutboxPublisherService outboxPublisherService;
    private final FocusMetricsService focusMetricsService;
    private final ObjectMapper objectMapper;

    public PlanGenerationService(
        SessionPlanRepository sessionPlanRepository,
        PlanMemoryRepository planMemoryRepository,
        FocusSessionRepository focusSessionRepository,
        SessionPolicyAuditRepository sessionPolicyAuditRepository,
        AiProviderAclService aiProviderAclService,
        OutboxPublisherService outboxPublisherService,
        FocusMetricsService focusMetricsService,
        ObjectMapper objectMapper
    ) {
        this.sessionPlanRepository = sessionPlanRepository;
        this.planMemoryRepository = planMemoryRepository;
        this.focusSessionRepository = focusSessionRepository;
        this.sessionPolicyAuditRepository = sessionPolicyAuditRepository;
        this.aiProviderAclService = aiProviderAclService;
        this.outboxPublisherService = outboxPublisherService;
        this.focusMetricsService = focusMetricsService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public SessionPlanArtifactDocument generatePlan(String sessionId, String goal, Integer durationMinutes) {
        long startedAtNanos = System.nanoTime();
        try {
            TenantScope scope = resolveScope();
            FocusSessionDocument session = loadOwnedSession(sessionId, scope);

            String effectiveGoal = resolveGoal(goal, session);
            int effectiveDurationMinutes = resolveDurationMinutes(durationMinutes, session.getDurationMinutes());
            int durationToleranceMinutes = calculateDurationTolerance(effectiveDurationMinutes);

            PlanMemoryDocument planMemory = planMemoryRepository
                .findByTenantIdAndOrgIdAndUserId(scope.tenantId(), scope.orgId(), scope.userId())
                .orElse(null);

            String planId = UUID.randomUUID().toString();
            AiProviderAclService.ProviderPlanResult providerResult = aiProviderAclService.requestPlan(
                planId,
                sessionId,
                effectiveGoal,
                effectiveDurationMinutes,
                planMemory
            );

            List<TaskBlockEmbedded> normalizedTaskBlocks = copyTaskBlocks(providerResult.taskBlocks());
            validatePlanStructure(normalizedTaskBlocks, effectiveDurationMinutes);

            int totalEstimatedMinutes = sumEstimatedMinutes(normalizedTaskBlocks);
            Instant generatedAt = Instant.now();

            SessionPlanArtifactDocument plan = new SessionPlanArtifactDocument();
            plan.setId(planId);
            plan.setTenantId(scope.tenantId());
            plan.setOrgId(scope.orgId());
            plan.setUserId(scope.userId());
            plan.setPlanId(planId);
            plan.setSessionId(sessionId);
            plan.setStatus(SessionPlanStatus.GENERATED);
            plan.setTemplateVersion(StringUtils.hasText(providerResult.templateVersion())
                ? providerResult.templateVersion()
                : DEFAULT_TEMPLATE_VERSION);
            plan.setProvider(StringUtils.hasText(providerResult.provider()) ? providerResult.provider() : DEFAULT_PROVIDER);
            plan.setTaskBlocks(normalizedTaskBlocks);
            plan.setRevision(1);
            plan.setSessionDurationMinutes(effectiveDurationMinutes);
            plan.setDurationToleranceMinutes(durationToleranceMinutes);
            plan.setGeneratedAt(generatedAt);
            plan.setRevisions(new ArrayList<>(List.of(
                toRevision(
                    1,
                    plan.getTemplateVersion(),
                    plan.getProvider(),
                    normalizedTaskBlocks,
                    generatedAt
                )
            )));

            SessionPlanArtifactDocument savedPlan = sessionPlanRepository.save(plan);

            String correlationId = resolveCorrelationId(null);
            String eventId = deterministicEventId(correlationId, "SessionPlanGenerated", savedPlan.getPlanId());

            SessionPlanGeneratedEvent event = new SessionPlanGeneratedEvent(
                eventId,
                generatedAt,
                scope.tenantId(),
                scope.orgId(),
                scope.userId(),
                savedPlan.getSessionId(),
                savedPlan.getPlanId(),
                savedPlan.getRevision(),
                savedPlan.getTaskBlocks().size(),
                totalEstimatedMinutes,
                effectiveDurationMinutes,
                savedPlan.getProvider(),
                savedPlan.getTemplateVersion(),
                providerResult.fallbackUsed()
            );

            publishPlanEvent(
                event.eventId(),
                savedPlan.getPlanId(),
                "SessionPlanGenerated",
                event,
                correlationId,
                event.eventId()
            );

            return savedPlan;
        } finally {
            focusMetricsService.recordAiGenerateLatency(
                Duration.ofNanos(System.nanoTime() - startedAtNanos)
            );
        }
    }

    public void validatePlanStructure(List<TaskBlockEmbedded> taskBlocks, Integer sessionDurationMinutes) {
        if (taskBlocks == null || taskBlocks.isEmpty()) {
            throw new IllegalStateException("Plan must contain at least one task block");
        }

        int totalEstimatedMinutes = 0;
        for (int index = 0; index < taskBlocks.size(); index++) {
            TaskBlockEmbedded block = taskBlocks.get(index);
            if (block == null) {
                throw new IllegalStateException("Plan task block at index " + index + " is required");
            }

            if (!StringUtils.hasText(block.getTitle())) {
                throw new IllegalStateException("Task block title is required at index " + index);
            }

            block.setTitle(block.getTitle().trim());
            if (StringUtils.hasText(block.getDetails())) {
                block.setDetails(block.getDetails().trim());
            }

            if (block.getEstimateMinutes() == null || block.getEstimateMinutes() <= 0) {
                throw new IllegalStateException("Task block estimateMinutes must be positive at index " + index);
            }

            block.setEstimateMinutes(Math.clamp(block.getEstimateMinutes(), 1, 360));
            totalEstimatedMinutes += block.getEstimateMinutes();

            int sequence = block.getSequence() != null && block.getSequence() > 0 ? block.getSequence() : index + 1;
            block.setSequence(sequence);

            if (!StringUtils.hasText(block.getBlockId())) {
                String idMaterial = sequence + ":" + block.getTitle().toLowerCase(Locale.ROOT);
                block.setBlockId(UUID.nameUUIDFromBytes(idMaterial.getBytes(StandardCharsets.UTF_8)).toString());
            }
        }

        if (sessionDurationMinutes != null && sessionDurationMinutes > 0) {
            int tolerance = calculateDurationTolerance(sessionDurationMinutes);
            if (Math.abs(totalEstimatedMinutes - sessionDurationMinutes) > tolerance) {
                throw new IllegalStateException(
                    "Plan duration mismatch: estimatedMinutes=" + totalEstimatedMinutes
                        + ", sessionDurationMinutes=" + sessionDurationMinutes
                        + ", toleranceMinutes=" + tolerance
                );
            }
        }
    }

    @Transactional
    public SessionPlanArtifactDocument applyUserEdits(
        String planId,
        List<TaskBlockEmbedded> taskBlocks,
        Long expectedRevision
    ) {
        TenantScope scope = resolveScope();
        SessionPlanArtifactDocument plan = loadOwnedPlan(planId, scope);
        ensureEditableBeforeSessionStart(plan, scope);

        int currentRevision = safeRevision(plan.getRevision());
        if (expectedRevision != null && expectedRevision.longValue() != currentRevision) {
            throw new IllegalStateException(
                "Revision conflict: expectedRevision=" + expectedRevision + ", currentRevision=" + currentRevision
            );
        }

        List<TaskBlockEmbedded> normalizedTaskBlocks = copyTaskBlocks(taskBlocks);
        validatePlanStructure(normalizedTaskBlocks, plan.getSessionDurationMinutes());

        int nextRevision = currentRevision + 1;
        int totalEstimatedMinutes = sumEstimatedMinutes(normalizedTaskBlocks);
        Instant revisedAt = Instant.now();

        plan.setTaskBlocks(normalizedTaskBlocks);
        plan.setRevision(nextRevision);
        plan.setStatus(SessionPlanStatus.EDITED);
        if (plan.getRevisions() == null) {
            plan.setRevisions(new ArrayList<>());
        }
        plan.getRevisions().add(toRevision(
            nextRevision,
            plan.getTemplateVersion(),
            plan.getProvider(),
            normalizedTaskBlocks,
            revisedAt
        ));

        SessionPlanArtifactDocument savedPlan = sessionPlanRepository.save(plan);

        String correlationId = resolveCorrelationId(null);
        String eventId = deterministicEventId(
            correlationId,
            "SessionPlanEdited",
            savedPlan.getPlanId() + ":" + nextRevision
        );

        SessionPlanEditedEvent event = new SessionPlanEditedEvent(
            eventId,
            revisedAt,
            scope.tenantId(),
            scope.orgId(),
            scope.userId(),
            savedPlan.getSessionId(),
            savedPlan.getPlanId(),
            currentRevision,
            nextRevision,
            normalizedTaskBlocks.size(),
            totalEstimatedMinutes
        );

        publishPlanEvent(
            event.eventId(),
            savedPlan.getPlanId(),
            "SessionPlanEdited",
            event,
            correlationId,
            event.eventId()
        );

        return savedPlan;
    }

    @Transactional
    public PlanMemoryDocument updatePlanMemory(String feedback, String outcome) {
        TenantScope scope = resolveScope();
        assertPlanMemoryEntitled(scope);

        String normalizedFeedback = normalizeNullable(feedback);
        String normalizedOutcome = normalizeNullable(outcome);

        if (!StringUtils.hasText(normalizedFeedback) && !StringUtils.hasText(normalizedOutcome)) {
            throw new IllegalStateException("Either feedback or outcome is required to update plan memory");
        }

        PlanMemoryDocument memory = planMemoryRepository
            .findByTenantIdAndOrgIdAndUserId(scope.tenantId(), scope.orgId(), scope.userId())
            .orElseGet(PlanMemoryDocument::new);

        memory.setTenantId(scope.tenantId());
        memory.setOrgId(scope.orgId());
        memory.setUserId(scope.userId());
        memory.setMemoryVersion(nextMemoryVersion(memory.getMemoryVersion()));
        if (!StringUtils.hasText(memory.getTemplateVersion())) {
            memory.setTemplateVersion(DEFAULT_TEMPLATE_VERSION);
        }
        if (!StringUtils.hasText(memory.getPreferredProvider())) {
            memory.setPreferredProvider(DEFAULT_PROVIDER);
        }
        if (memory.getStablePreferences() == null) {
            memory.setStablePreferences(new ArrayList<>());
        }
        if (memory.getAvoidPatterns() == null) {
            memory.setAvoidPatterns(new ArrayList<>());
        }

        if (StringUtils.hasText(normalizedFeedback)) {
            appendMemoryEntry(memory.getStablePreferences(), normalizedFeedback);
        }

        if (StringUtils.hasText(normalizedOutcome)) {
            if (containsAvoidSignal(normalizedOutcome)) {
                appendMemoryEntry(memory.getAvoidPatterns(), normalizedOutcome);
            } else {
                appendMemoryEntry(memory.getStablePreferences(), normalizedOutcome);
            }
        }

        PlanMemoryDocument savedMemory = planMemoryRepository.save(memory);

        String correlationId = resolveCorrelationId(null);
        String aggregateId = StringUtils.hasText(savedMemory.getId()) ? savedMemory.getId() : scope.userId();
        String eventId = deterministicEventId(
            correlationId,
            "PlanLearningMemoryUpdated",
            aggregateId + ":" + savedMemory.getMemoryVersion()
        );

        PlanLearningMemoryUpdatedEvent event = new PlanLearningMemoryUpdatedEvent(
            eventId,
            Instant.now(),
            scope.tenantId(),
            scope.orgId(),
            scope.userId(),
            aggregateId,
            savedMemory.getMemoryVersion(),
            savedMemory.getLastPlanId(),
            savedMemory.getLastSessionId(),
            normalizedFeedback,
            normalizedOutcome
        );

        publishPlanEvent(
            event.eventId(),
            aggregateId,
            "PlanLearningMemoryUpdated",
            event,
            correlationId,
            event.eventId()
        );

        return savedMemory;
    }

    private void assertPlanMemoryEntitled(TenantScope scope) {
        String policyStateId = policyStateId(PLAN_MEMORY_POLICY, scope.userId());
        Optional<String> decision = sessionPolicyAuditRepository
            .findByIdAndTenantIdAndOrgId(policyStateId, scope.tenantId(), scope.orgId())
            .map(document -> document.getDecision() != null ? document.getDecision().trim() : null);

        if (decision.isPresent() && !ALLOW.equalsIgnoreCase(decision.get())) {
            throw new IllegalStateException(ApiErrorCode.ENTITLEMENT_DENIED.name());
        }
    }

    private void appendMemoryEntry(List<String> entries, String newEntry) {
        if (entries == null || !StringUtils.hasText(newEntry)) {
            return;
        }

        String normalized = newEntry.trim();
        if (entries.stream().noneMatch(existing -> normalized.equalsIgnoreCase(existing))) {
            entries.add(normalized);
        }

        if (entries.size() > MAX_MEMORY_ITEMS) {
            int overflow = entries.size() - MAX_MEMORY_ITEMS;
            entries.subList(0, overflow).clear();
        }
    }

    private boolean containsAvoidSignal(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        return normalized.contains("avoid")
            || normalized.contains("too long")
            || normalized.contains("distract")
            || normalized.contains("overwhelm")
            || normalized.contains("blocked")
            || normalized.contains("didn't work")
            || normalized.contains("did not work");
    }

    private long nextMemoryVersion(Long currentMemoryVersion) {
        if (currentMemoryVersion == null || currentMemoryVersion < 0) {
            return 1L;
        }
        return currentMemoryVersion + 1L;
    }

    private String resolveGoal(String requestedGoal, FocusSessionDocument session) {
        if (StringUtils.hasText(requestedGoal)) {
            return requestedGoal.trim();
        }

        if (session.getGoal() != null && StringUtils.hasText(session.getGoal().getText())) {
            return session.getGoal().getText().trim();
        }

        throw new IllegalStateException("Goal is required for plan generation");
    }

    private int resolveDurationMinutes(Integer requestedDurationMinutes, Integer sessionDurationMinutes) {
        int effective = requestedDurationMinutes != null ? requestedDurationMinutes : (sessionDurationMinutes != null ? sessionDurationMinutes : 0);
        if (effective <= 0) {
            throw new IllegalStateException("Duration minutes must be greater than zero");
        }
        return Math.clamp(effective, 15, 240);
    }

    private int calculateDurationTolerance(int durationMinutes) {
        return Math.max(5, Math.min(30, durationMinutes / 3));
    }

    private int safeRevision(Integer revision) {
        return revision != null && revision > 0 ? revision : 0;
    }

    private int sumEstimatedMinutes(List<TaskBlockEmbedded> taskBlocks) {
        if (taskBlocks == null || taskBlocks.isEmpty()) {
            return 0;
        }

        return taskBlocks.stream()
            .filter(block -> block != null && block.getEstimateMinutes() != null)
            .mapToInt(TaskBlockEmbedded::getEstimateMinutes)
            .sum();
    }

    private List<TaskBlockEmbedded> copyTaskBlocks(List<TaskBlockEmbedded> taskBlocks) {
        List<TaskBlockEmbedded> copy = new ArrayList<>();
        if (taskBlocks == null) {
            return copy;
        }

        for (TaskBlockEmbedded block : taskBlocks) {
            if (block == null) {
                copy.add(null);
                continue;
            }

            copy.add(new TaskBlockEmbedded(
                block.getBlockId(),
                block.getTitle(),
                block.getDetails(),
                block.getEstimateMinutes(),
                block.getSequence()
            ));
        }

        return copy;
    }

    private PlanRevisionEmbedded toRevision(
        int revision,
        String templateVersion,
        String provider,
        List<TaskBlockEmbedded> taskBlocks,
        Instant revisedAt
    ) {
        return new PlanRevisionEmbedded(
            revision,
            templateVersion,
            provider,
            sumEstimatedMinutes(taskBlocks),
            revisedAt,
            copyTaskBlocks(taskBlocks)
        );
    }

    private void ensureEditableBeforeSessionStart(SessionPlanArtifactDocument plan, TenantScope scope) {
        if (!StringUtils.hasText(plan.getSessionId())) {
            throw new IllegalStateException("Plan must be associated with a session before edits");
        }

        FocusSessionDocument session = loadOwnedSession(plan.getSessionId(), scope);
        if (session.getStatus() != FocusSessionStatus.CREATED) {
            throw new IllegalStateException("Plan can only be edited before session start");
        }
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

    private String policyStateId(String policyName, String userId) {
        return "policy:" + policyName + ":" + userId;
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
            throw new IllegalStateException("Unable to serialize AI plan event payload", ex);
        }
    }

    private String normalizeNullable(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
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
