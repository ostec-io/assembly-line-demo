package io.ostec.focusflow.service.entitlement;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ostec.focusflow.audit.AuditLogService;
import io.ostec.focusflow.document.entitlement.BillingWebhookInboxDocument;
import io.ostec.focusflow.document.entitlement.SubscriptionAccountDocument;
import io.ostec.focusflow.document.entitlement.SubscriptionPlan;
import io.ostec.focusflow.document.entitlement.SubscriptionState;
import io.ostec.focusflow.event.model.entitlement.EntitlementGrantedEvent;
import io.ostec.focusflow.event.model.entitlement.EntitlementRevokedEvent;
import io.ostec.focusflow.event.model.entitlement.SubscriptionActivatedEvent;
import io.ostec.focusflow.event.model.entitlement.SubscriptionCanceledEvent;
import io.ostec.focusflow.event.model.entitlement.SubscriptionPastDueEvent;
import io.ostec.focusflow.event.model.entitlement.SubscriptionRenewedEvent;
import io.ostec.focusflow.event.publisher.OutboxPublisherService;
import io.ostec.focusflow.exception.ApiErrorCode;
import io.ostec.focusflow.repository.entitlement.BillingWebhookInboxRepository;
import io.ostec.focusflow.repository.entitlement.SubscriptionAccountRepository;
import io.ostec.foundation.security.tenant.TenantContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class SubscriptionLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionLifecycleService.class);

    private static final String AGGREGATE_TYPE = "SubscriptionAccount";
    private static final String EVENT_VERSION = "1";
    private static final String WEBHOOK_STATUS_RECEIVED = "RECEIVED";
    private static final String WEBHOOK_STATUS_PROCESSED = "PROCESSED";
    private static final String WEBHOOK_STATUS_FAILED = "FAILED";
    private static final List<String> PRO_ENTITLEMENTS = List.of(
        "ai.plan-memory",
        "accountability.private-room",
        "focus.deep-work.analytics"
    );

    private final SubscriptionAccountRepository subscriptionAccountRepository;
    private final BillingWebhookInboxRepository billingWebhookInboxRepository;
    private final OutboxPublisherService outboxPublisherService;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;

    public SubscriptionLifecycleService(
        SubscriptionAccountRepository subscriptionAccountRepository,
        BillingWebhookInboxRepository billingWebhookInboxRepository,
        OutboxPublisherService outboxPublisherService,
        AuditLogService auditLogService,
        ObjectMapper objectMapper
    ) {
        this.subscriptionAccountRepository = subscriptionAccountRepository;
        this.billingWebhookInboxRepository = billingWebhookInboxRepository;
        this.outboxPublisherService = outboxPublisherService;
        this.auditLogService = auditLogService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public SubscriptionAccountDocument activateProSubscription(String userId, Instant cycleStart, Instant cycleEnd) {
        TenantScope scope = resolveScope();
        return activateProSubscriptionInternal(scope, userId, cycleStart, cycleEnd, null, false);
    }

    @Transactional
    public SubscriptionAccountDocument cancelSubscription(String userId, String reason) {
        TenantScope scope = resolveScope();
        return cancelSubscriptionInternal(scope, userId, reason, null);
    }

    @Transactional
    public boolean recordBillingWebhook(String provider, String eventId, String eventType, String payload) {
        TenantScope scope = resolveScope();
        validateWebhookInput(provider, eventId, eventType);

        String normalizedProvider = provider.trim().toLowerCase(Locale.ROOT);
        String normalizedEventId = eventId.trim();

        if (billingWebhookInboxRepository.existsByTenantIdAndOrgIdAndProviderAndEventId(
            scope.tenantId(),
            scope.orgId(),
            normalizedProvider,
            normalizedEventId
        )) {
            log.info(
                "Ignoring duplicate billing webhook eventId={} provider={} tenantId={} orgId={}",
                normalizedEventId,
                normalizedProvider,
                scope.tenantId(),
                scope.orgId()
            );
            return false;
        }

        BillingWebhookInboxDocument inboxDocument = new BillingWebhookInboxDocument();
        inboxDocument.setTenantId(scope.tenantId());
        inboxDocument.setOrgId(scope.orgId());
        inboxDocument.setProvider(normalizedProvider);
        inboxDocument.setEventId(normalizedEventId);
        inboxDocument.setEventHash(hashPayload(payload));
        inboxDocument.setProcessingStatus(WEBHOOK_STATUS_RECEIVED);
        inboxDocument.setReceivedAt(Instant.now());

        try {
            billingWebhookInboxRepository.save(inboxDocument);
        } catch (DuplicateKeyException ex) {
            log.info(
                "Ignoring duplicate billing webhook due to concurrent insert eventId={} provider={} tenantId={} orgId={}",
                normalizedEventId,
                normalizedProvider,
                scope.tenantId(),
                scope.orgId()
            );
            return false;
        }

        try {
            JsonNode payloadNode = parsePayload(payload);
            String normalizedType = normalizeEventType(eventType);
            applyWebhookTransition(scope, normalizedType, normalizedEventId, payloadNode);

            inboxDocument.setProcessingStatus(WEBHOOK_STATUS_PROCESSED);
            inboxDocument.setProcessedAt(Instant.now());
            inboxDocument.setFailureReason(null);
            billingWebhookInboxRepository.save(inboxDocument);
            return true;
        } catch (Exception ex) {
            inboxDocument.setProcessingStatus(WEBHOOK_STATUS_FAILED);
            inboxDocument.setProcessedAt(Instant.now());
            inboxDocument.setFailureReason(truncate(ex.getMessage(), 500));
            billingWebhookInboxRepository.save(inboxDocument);
            throw new IllegalStateException("Billing webhook processing failed", ex);
        }
    }

    private void validateWebhookInput(String provider, String eventId, String eventType) {
        if (!StringUtils.hasText(provider)) {
            throw new IllegalStateException("Webhook provider is required");
        }
        if (!StringUtils.hasText(eventId)) {
            throw new IllegalStateException("Webhook eventId is required");
        }
        if (!StringUtils.hasText(eventType)) {
            throw new IllegalStateException("Webhook eventType is required");
        }
    }

    private void applyWebhookTransition(
        TenantScope scope,
        String normalizedEventType,
        String sourceEventId,
        JsonNode payload
    ) {
        String targetUserId = resolveTargetUserId(payload, scope.userId());
        Instant cycleStart = resolveCycleStart(payload);
        Instant cycleEnd = resolveCycleEnd(payload, cycleStart);
        String reason = extractText(payload, "reason", "message", "description", "failureReason");

        if (isCanceledType(normalizedEventType)) {
            cancelSubscriptionInternal(scope, targetUserId, reason, sourceEventId);
            return;
        }

        if (isPastDueType(normalizedEventType)) {
            markPastDueInternal(scope, targetUserId, reason, sourceEventId);
            return;
        }

        if (isRenewalType(normalizedEventType) || isActivationType(normalizedEventType)) {
            activateProSubscriptionInternal(
                scope,
                targetUserId,
                cycleStart,
                cycleEnd,
                sourceEventId,
                isRenewalType(normalizedEventType)
            );
            return;
        }

        log.info(
            "No transition mapping for billing webhook eventType={} tenantId={} orgId={}",
            normalizedEventType,
            scope.tenantId(),
            scope.orgId()
        );
    }

    private SubscriptionAccountDocument activateProSubscriptionInternal(
        TenantScope scope,
        String userId,
        Instant cycleStart,
        Instant cycleEnd,
        String sourceEventId,
        boolean renewalHint
    ) {
        String effectiveUserId = resolveEffectiveUserId(userId, scope.userId());
        SubscriptionAccountDocument account = loadOrCreateAccount(scope, effectiveUserId);
        SubscriptionPlan previousPlan = account.getPlan();
        SubscriptionState previousState = account.getState();

        Instant previousCycleEnd = account.getCycleEnd();
        boolean wasActivePro = account.getPlan() == SubscriptionPlan.PRO && account.getState() == SubscriptionState.ACTIVE;

        Instant now = Instant.now();
        Instant effectiveCycleStart = cycleStart != null ? cycleStart : now;
        Instant effectiveCycleEnd = cycleEnd != null ? cycleEnd : effectiveCycleStart.plus(Duration.ofDays(30));

        account.setPlan(SubscriptionPlan.PRO);
        account.setState(SubscriptionState.ACTIVE);
        account.setCycleStart(effectiveCycleStart);
        account.setCycleEnd(effectiveCycleEnd);
        account.setEntitlements(new ArrayList<>(PRO_ENTITLEMENTS));
        account.setLastWebhookEventId(sourceEventId);

        SubscriptionAccountDocument savedAccount = subscriptionAccountRepository.save(account);
        String correlationId = resolveCorrelationId(sourceEventId);
        String transitionReason = renewalHint || wasActivePro ? "Subscription renewed" : "Subscription activated";

        auditLogService.logBillingTransition(
            scope.tenantId(),
            scope.orgId(),
            effectiveUserId,
            previousPlan != null ? previousPlan.name() : null,
            previousState != null ? previousState.name() : null,
            savedAccount.getPlan() != null ? savedAccount.getPlan().name() : null,
            savedAccount.getState() != null ? savedAccount.getState().name() : null,
            transitionReason,
            sourceEventId,
            correlationId
        );

        if (renewalHint || wasActivePro) {
            publishSubscriptionRenewed(savedAccount, previousCycleEnd, sourceEventId, correlationId);
        } else {
            publishSubscriptionActivated(savedAccount, sourceEventId, correlationId);
        }

        publishEntitlementGranted(savedAccount, sourceEventId, correlationId, "PRO subscription active");
        return savedAccount;
    }

    private SubscriptionAccountDocument cancelSubscriptionInternal(
        TenantScope scope,
        String userId,
        String reason,
        String sourceEventId
    ) {
        String effectiveUserId = resolveEffectiveUserId(userId, scope.userId());
        SubscriptionAccountDocument account = loadOrCreateAccount(scope, effectiveUserId);
        SubscriptionPlan previousPlan = account.getPlan();
        SubscriptionState previousState = account.getState();
        List<String> revokedEntitlements = resolveRevokedEntitlements(account);

        account.setPlan(SubscriptionPlan.FREE);
        account.setState(SubscriptionState.CANCELED);
        account.setCycleEnd(Instant.now());
        account.setEntitlements(new ArrayList<>());
        account.setLastWebhookEventId(sourceEventId);

        SubscriptionAccountDocument savedAccount = subscriptionAccountRepository.save(account);
        String correlationId = resolveCorrelationId(sourceEventId);
        String effectiveReason = StringUtils.hasText(reason) ? reason.trim() : "Subscription canceled";

        auditLogService.logBillingTransition(
            scope.tenantId(),
            scope.orgId(),
            effectiveUserId,
            previousPlan != null ? previousPlan.name() : null,
            previousState != null ? previousState.name() : null,
            savedAccount.getPlan() != null ? savedAccount.getPlan().name() : null,
            savedAccount.getState() != null ? savedAccount.getState().name() : null,
            effectiveReason,
            sourceEventId,
            correlationId
        );

        publishSubscriptionCanceled(savedAccount, effectiveReason, sourceEventId, correlationId);
        publishEntitlementRevoked(savedAccount, revokedEntitlements, sourceEventId, correlationId, effectiveReason);
        return savedAccount;
    }

    private SubscriptionAccountDocument markPastDueInternal(
        TenantScope scope,
        String userId,
        String reason,
        String sourceEventId
    ) {
        String effectiveUserId = resolveEffectiveUserId(userId, scope.userId());
        SubscriptionAccountDocument account = loadOrCreateAccount(scope, effectiveUserId);
        SubscriptionPlan previousPlan = account.getPlan();
        SubscriptionState previousState = account.getState();
        List<String> revokedEntitlements = resolveRevokedEntitlements(account);

        if (account.getPlan() == null) {
            account.setPlan(SubscriptionPlan.PRO);
        }
        account.setState(SubscriptionState.PAST_DUE);
        account.setEntitlements(new ArrayList<>());
        account.setLastWebhookEventId(sourceEventId);

        SubscriptionAccountDocument savedAccount = subscriptionAccountRepository.save(account);
        String correlationId = resolveCorrelationId(sourceEventId);
        String effectiveReason = StringUtils.hasText(reason) ? reason.trim() : "Subscription past due";

        auditLogService.logBillingTransition(
            scope.tenantId(),
            scope.orgId(),
            effectiveUserId,
            previousPlan != null ? previousPlan.name() : null,
            previousState != null ? previousState.name() : null,
            savedAccount.getPlan() != null ? savedAccount.getPlan().name() : null,
            savedAccount.getState() != null ? savedAccount.getState().name() : null,
            effectiveReason,
            sourceEventId,
            correlationId
        );

        publishSubscriptionPastDue(savedAccount, effectiveReason, sourceEventId, correlationId);
        publishEntitlementRevoked(savedAccount, revokedEntitlements, sourceEventId, correlationId, effectiveReason);
        return savedAccount;
    }

    private SubscriptionAccountDocument loadOrCreateAccount(TenantScope scope, String userId) {
        return subscriptionAccountRepository
            .findByTenantIdAndOrgIdAndUserId(scope.tenantId(), scope.orgId(), userId)
            .orElseGet(() -> {
                SubscriptionAccountDocument document = new SubscriptionAccountDocument();
                document.setTenantId(scope.tenantId());
                document.setOrgId(scope.orgId());
                document.setUserId(userId);
                document.setPlan(SubscriptionPlan.FREE);
                document.setState(SubscriptionState.EXPIRED);
                document.setEntitlements(new ArrayList<>());
                return document;
            });
    }

    private List<String> resolveRevokedEntitlements(SubscriptionAccountDocument account) {
        Set<String> normalized = new LinkedHashSet<>();

        if (account.getEntitlements() != null) {
            account.getEntitlements().stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .forEach(normalized::add);
        }

        if (normalized.isEmpty()) {
            normalized.addAll(PRO_ENTITLEMENTS);
        }

        return new ArrayList<>(normalized);
    }

    private void publishSubscriptionActivated(
        SubscriptionAccountDocument account,
        String sourceEventId,
        String correlationId
    ) {
        String eventId = deterministicEventId(
            account.getTenantId(),
            account.getOrgId(),
            account.getUserId(),
            "SubscriptionActivated",
            StringUtils.hasText(sourceEventId)
                ? sourceEventId
                : account.getCycleStart() + ":" + account.getCycleEnd()
        );
        SubscriptionActivatedEvent event = new SubscriptionActivatedEvent(
            eventId,
            Instant.now(),
            account.getTenantId(),
            account.getOrgId(),
            account.getUserId(),
            account.getPlan(),
            account.getCycleStart(),
            account.getCycleEnd(),
            sourceEventId
        );

        publishEvent(
            event.eventId(),
            account.getUserId(),
            "SubscriptionActivated",
            event,
            correlationId,
            StringUtils.hasText(sourceEventId) ? sourceEventId : event.eventId()
        );
    }

    private void publishSubscriptionRenewed(
        SubscriptionAccountDocument account,
        Instant previousCycleEnd,
        String sourceEventId,
        String correlationId
    ) {
        String eventId = deterministicEventId(
            account.getTenantId(),
            account.getOrgId(),
            account.getUserId(),
            "SubscriptionRenewed",
            StringUtils.hasText(sourceEventId)
                ? sourceEventId
                : account.getCycleStart() + ":" + account.getCycleEnd()
        );
        SubscriptionRenewedEvent event = new SubscriptionRenewedEvent(
            eventId,
            Instant.now(),
            account.getTenantId(),
            account.getOrgId(),
            account.getUserId(),
            account.getPlan(),
            previousCycleEnd,
            account.getCycleStart(),
            account.getCycleEnd(),
            sourceEventId
        );

        publishEvent(
            event.eventId(),
            account.getUserId(),
            "SubscriptionRenewed",
            event,
            correlationId,
            StringUtils.hasText(sourceEventId) ? sourceEventId : event.eventId()
        );
    }

    private void publishSubscriptionPastDue(
        SubscriptionAccountDocument account,
        String reason,
        String sourceEventId,
        String correlationId
    ) {
        String eventId = deterministicEventId(
            account.getTenantId(),
            account.getOrgId(),
            account.getUserId(),
            "SubscriptionPastDue",
            StringUtils.hasText(sourceEventId) ? sourceEventId : reason
        );
        SubscriptionPastDueEvent event = new SubscriptionPastDueEvent(
            eventId,
            Instant.now(),
            account.getTenantId(),
            account.getOrgId(),
            account.getUserId(),
            account.getPlan(),
            account.getState(),
            reason,
            sourceEventId
        );

        publishEvent(
            event.eventId(),
            account.getUserId(),
            "SubscriptionPastDue",
            event,
            correlationId,
            StringUtils.hasText(sourceEventId) ? sourceEventId : event.eventId()
        );
    }

    private void publishSubscriptionCanceled(
        SubscriptionAccountDocument account,
        String reason,
        String sourceEventId,
        String correlationId
    ) {
        String eventId = deterministicEventId(
            account.getTenantId(),
            account.getOrgId(),
            account.getUserId(),
            "SubscriptionCanceled",
            StringUtils.hasText(sourceEventId) ? sourceEventId : reason
        );
        SubscriptionCanceledEvent event = new SubscriptionCanceledEvent(
            eventId,
            Instant.now(),
            account.getTenantId(),
            account.getOrgId(),
            account.getUserId(),
            account.getPlan(),
            account.getState(),
            reason,
            sourceEventId
        );

        publishEvent(
            event.eventId(),
            account.getUserId(),
            "SubscriptionCanceled",
            event,
            correlationId,
            StringUtils.hasText(sourceEventId) ? sourceEventId : event.eventId()
        );
    }

    private void publishEntitlementGranted(
        SubscriptionAccountDocument account,
        String sourceEventId,
        String correlationId,
        String reason
    ) {
        for (String entitlement : account.getEntitlements()) {
            if (!StringUtils.hasText(entitlement)) {
                continue;
            }

            String normalizedEntitlement = entitlement.trim();
            String eventId = deterministicEventId(
                account.getTenantId(),
                account.getOrgId(),
                account.getUserId(),
                "EntitlementGranted",
                normalizedEntitlement + ":" + sourceEventId
            );
            EntitlementGrantedEvent event = new EntitlementGrantedEvent(
                eventId,
                Instant.now(),
                account.getTenantId(),
                account.getOrgId(),
                account.getUserId(),
                normalizedEntitlement,
                reason,
                sourceEventId
            );

            publishEvent(
                event.eventId(),
                account.getUserId(),
                "EntitlementGranted",
                event,
                correlationId,
                StringUtils.hasText(sourceEventId) ? sourceEventId : event.eventId()
            );

            auditLogService.logEntitlementGranted(
                account.getTenantId(),
                account.getOrgId(),
                account.getUserId(),
                normalizedEntitlement,
                reason,
                sourceEventId,
                correlationId
            );
        }
    }

    private void publishEntitlementRevoked(
        SubscriptionAccountDocument account,
        List<String> revokedEntitlements,
        String sourceEventId,
        String correlationId,
        String reason
    ) {
        for (String entitlement : revokedEntitlements) {
            if (!StringUtils.hasText(entitlement)) {
                continue;
            }

            String normalizedEntitlement = entitlement.trim();
            String eventId = deterministicEventId(
                account.getTenantId(),
                account.getOrgId(),
                account.getUserId(),
                "EntitlementRevoked",
                normalizedEntitlement + ":" + sourceEventId + ":" + reason
            );
            EntitlementRevokedEvent event = new EntitlementRevokedEvent(
                eventId,
                Instant.now(),
                account.getTenantId(),
                account.getOrgId(),
                account.getUserId(),
                normalizedEntitlement,
                reason,
                sourceEventId
            );

            publishEvent(
                event.eventId(),
                account.getUserId(),
                "EntitlementRevoked",
                event,
                correlationId,
                StringUtils.hasText(sourceEventId) ? sourceEventId : event.eventId()
            );

            auditLogService.logEntitlementRevoked(
                account.getTenantId(),
                account.getOrgId(),
                account.getUserId(),
                normalizedEntitlement,
                reason,
                sourceEventId,
                correlationId
            );
        }
    }

    private void publishEvent(
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
            throw new IllegalStateException("Unable to serialize subscription lifecycle event payload", ex);
        }
    }

    private JsonNode parsePayload(String payload) {
        if (!StringUtils.hasText(payload)) {
            return objectMapper.createObjectNode();
        }

        try {
            return objectMapper.readTree(payload);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to parse billing webhook payload", ex);
        }
    }

    private String resolveTargetUserId(JsonNode payload, String fallbackUserId) {
        String userId = extractText(
            payload,
            "userId",
            "subjectUserId",
            "ownerUserId",
            "subscriberId",
            "customerId",
            "accountId"
        );

        if (StringUtils.hasText(userId)) {
            return userId.trim();
        }

        if (StringUtils.hasText(fallbackUserId)) {
            return fallbackUserId.trim();
        }

        throw new IllegalStateException("Unable to resolve userId from webhook payload");
    }

    private String resolveEffectiveUserId(String requestedUserId, String scopeUserId) {
        if (StringUtils.hasText(requestedUserId)) {
            return requestedUserId.trim();
        }
        if (StringUtils.hasText(scopeUserId)) {
            return scopeUserId.trim();
        }
        throw new IllegalStateException("User scope is required");
    }

    private Instant resolveCycleStart(JsonNode payload) {
        return extractInstant(payload, "cycleStart", "currentPeriodStart", "periodStart", "startsAt", "effectiveAt");
    }

    private Instant resolveCycleEnd(JsonNode payload, Instant cycleStart) {
        Instant extracted = extractInstant(payload, "cycleEnd", "currentPeriodEnd", "periodEnd", "endsAt", "expiresAt");
        if (extracted != null) {
            return extracted;
        }

        Instant reference = cycleStart != null ? cycleStart : Instant.now();
        return reference.plus(Duration.ofDays(30));
    }

    private Instant extractInstant(JsonNode payload, String... keys) {
        if (payload == null) {
            return null;
        }

        for (String key : keys) {
            JsonNode value = payload.get(key);
            if (value == null || value.isNull()) {
                continue;
            }

            if (value.isNumber()) {
                long raw = value.asLong();
                if (raw <= 0) {
                    continue;
                }
                return raw > 9_999_999_999L ? Instant.ofEpochMilli(raw) : Instant.ofEpochSecond(raw);
            }

            if (value.isTextual()) {
                String text = value.asText();
                if (!StringUtils.hasText(text)) {
                    continue;
                }

                try {
                    return Instant.parse(text.trim());
                } catch (Exception ignored) {
                    // Continue trying other keys.
                }
            }
        }

        return null;
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

    private String normalizeEventType(String eventType) {
        if (!StringUtils.hasText(eventType)) {
            return "";
        }

        String normalized = eventType.trim().toLowerCase(Locale.ROOT)
            .replace('-', '_')
            .replace('.', '_');

        if (normalized.endsWith("_event")) {
            return normalized.substring(0, normalized.length() - "_event".length());
        }
        if (normalized.endsWith("event")) {
            return normalized.substring(0, normalized.length() - "event".length());
        }
        return normalized;
    }

    private boolean isActivationType(String normalizedEventType) {
        return normalizedEventType.contains("activat")
            || normalizedEventType.contains("created")
            || normalizedEventType.contains("started")
            || normalizedEventType.contains("trial_converted");
    }

    private boolean isRenewalType(String normalizedEventType) {
        return normalizedEventType.contains("renew")
            || normalizedEventType.contains("invoice_paid")
            || normalizedEventType.contains("payment_succeeded");
    }

    private boolean isPastDueType(String normalizedEventType) {
        return normalizedEventType.contains("past_due")
            || normalizedEventType.contains("payment_failed")
            || normalizedEventType.contains("invoice_failed")
            || normalizedEventType.contains("delinquent");
    }

    private boolean isCanceledType(String normalizedEventType) {
        return normalizedEventType.contains("cancel")
            || normalizedEventType.contains("deleted")
            || normalizedEventType.contains("terminated");
    }

    private String hashPayload(String payload) {
        String material = payload != null ? payload : "";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(material.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(hashBytes.length * 2);
            for (byte hashByte : hashBytes) {
                builder.append(String.format("%02x", hashByte));
            }
            return builder.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to hash billing webhook payload", ex);
        }
    }

    private String truncate(String value, int maxLength) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private String deterministicEventId(
        String tenantId,
        String orgId,
        String userId,
        String eventType,
        String suffix
    ) {
        String material = tenantId + ":" + orgId + ":" + userId + ":" + eventType + ":" + suffix;
        return UUID.nameUUIDFromBytes(material.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private String resolveCorrelationId(String sourceEventId) {
        if (StringUtils.hasText(sourceEventId)) {
            return sourceEventId;
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
