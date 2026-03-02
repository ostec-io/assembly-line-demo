package io.ostec.focusflow.event.listener.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ostec.focusflow.document.session.FocusSessionDocument;
import io.ostec.focusflow.document.session.FocusSessionStatus;
import io.ostec.focusflow.event.model.DomainEventEnvelope;
import io.ostec.focusflow.repository.session.FocusSessionRepository;
import io.ostec.focusflow.service.session.SessionCommandService;
import io.ostec.focusflow.service.session.SessionPolicyService;
import io.ostec.foundation.security.tenant.TenantContext;
import io.ostec.foundation.security.tenant.TenantContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Optional;

@Component
public class SessionEntitlementEventListener {

    private static final Logger log = LoggerFactory.getLogger(SessionEntitlementEventListener.class);

    private final SessionPolicyService sessionPolicyService;
    private final SessionCommandService sessionCommandService;
    private final FocusSessionRepository focusSessionRepository;
    private final ObjectMapper objectMapper;

    public SessionEntitlementEventListener(
        SessionPolicyService sessionPolicyService,
        SessionCommandService sessionCommandService,
        FocusSessionRepository focusSessionRepository,
        ObjectMapper objectMapper
    ) {
        this.sessionPolicyService = sessionPolicyService;
        this.sessionCommandService = sessionCommandService;
        this.focusSessionRepository = focusSessionRepository;
        this.objectMapper = objectMapper;
    }

    @EventListener
    public void onDomainEvent(DomainEventEnvelope envelope) {
        String normalizedType = normalizeEventType(envelope.eventType());
        if (!"EntitlementGranted".equals(normalizedType)
            && !"EntitlementRevoked".equals(normalizedType)
            && !"UsageLimitReached".equals(normalizedType)) {
            return;
        }

        JsonNode payload = parsePayload(envelope.payload());
        String userId = resolveUserId(payload, envelope);
        if (!StringUtils.hasText(userId)) {
            log.warn("Skipping {} because userId could not be resolved from payload", normalizedType);
            return;
        }

        withTenantContext(envelope, userId, () -> handlePolicyEvent(normalizedType, payload, envelope));
    }

    private void handlePolicyEvent(String normalizedType, JsonNode payload, DomainEventEnvelope envelope) {
        switch (normalizedType) {
            case "EntitlementGranted" -> handleEntitlementGranted(payload, envelope.eventId());
            case "EntitlementRevoked" -> handleEntitlementRevoked(payload, envelope.eventId());
            case "UsageLimitReached" -> handleUsageLimitReached(payload, envelope.eventId());
            default -> {
                // No-op: guarded by caller.
            }
        }
    }

    private void handleEntitlementGranted(JsonNode payload, String sourceEventId) {
        String userId = extractText(payload, "userId", "subjectUserId", "ownerUserId");
        sessionPolicyService.upsertUserPolicyState(
            "entitlement",
            userId,
            "ALLOW",
            "Entitlement granted",
            "sourceEventId=" + sourceEventId
        );
    }

    private void handleEntitlementRevoked(JsonNode payload, String sourceEventId) {
        String userId = extractText(payload, "userId", "subjectUserId", "ownerUserId");
        String reason = extractText(payload, "reason", "revocationReason", "message");

        sessionPolicyService.upsertUserPolicyState(
            "entitlement",
            userId,
            "DENY",
            StringUtils.hasText(reason) ? reason : "Entitlement revoked",
            "sourceEventId=" + sourceEventId
        );
    }

    private void handleUsageLimitReached(JsonNode payload, String sourceEventId) {
        String userId = extractText(payload, "userId", "subjectUserId", "ownerUserId");
        String reason = extractText(payload, "reason", "limitName", "message");
        String effectiveReason = StringUtils.hasText(reason) ? reason : "Usage limit reached";

        sessionPolicyService.upsertUserPolicyState(
            "usage-limit",
            userId,
            "BLOCK",
            effectiveReason,
            "sourceEventId=" + sourceEventId
        );

        String tenantId = TenantContextHolder.requireTenantId();
        String orgId = TenantContextHolder.requireOrgId();
        String scopedUserId = TenantContextHolder.requireUserId();

        Optional<FocusSessionDocument> activeSession = focusSessionRepository.findFirstByTenantIdAndOrgIdAndUserIdAndStatus(
            tenantId,
            orgId,
            scopedUserId,
            FocusSessionStatus.ACTIVE
        );

        activeSession.ifPresent(session -> sessionCommandService.abortSession(session.getId(), effectiveReason));
    }

    private void withTenantContext(DomainEventEnvelope envelope, String userId, Runnable action) {
        if (!StringUtils.hasText(envelope.tenantId()) || !StringUtils.hasText(envelope.orgId())) {
            log.warn("Skipping event {} because tenant/org context is missing", envelope.eventType());
            return;
        }

        TenantContext previous = TenantContextHolder.getContext();
        boolean hadPreviousContext = TenantContextHolder.hasContext();

        try {
            TenantContextHolder.setContext(TenantContext.builder()
                .tenantId(envelope.tenantId())
                .orgId(envelope.orgId())
                .userId(userId)
                .correlationId(StringUtils.hasText(envelope.correlationId()) ? envelope.correlationId() : envelope.eventId())
                .build());
            action.run();
        } finally {
            if (hadPreviousContext && previous != null && previous.hasAnyContext()) {
                TenantContextHolder.setContext(previous);
            } else {
                TenantContextHolder.clear();
            }
        }
    }

    private JsonNode parsePayload(String payload) {
        if (!StringUtils.hasText(payload)) {
            return objectMapper.createObjectNode();
        }

        try {
            return objectMapper.readTree(payload);
        } catch (Exception ex) {
            log.warn("Unable to parse incoming session entitlement payload", ex);
            return objectMapper.createObjectNode();
        }
    }

    private String resolveUserId(JsonNode payload, DomainEventEnvelope envelope) {
        String payloadUserId = extractText(payload, "userId", "subjectUserId", "ownerUserId", "actorUserId");
        if (StringUtils.hasText(payloadUserId)) {
            return payloadUserId;
        }

        if (StringUtils.hasText(envelope.aggregateId())) {
            return envelope.aggregateId();
        }

        String producer = envelope.producer();
        if (StringUtils.hasText(producer) && producer.contains(":")) {
            String[] segments = producer.split(":", 2);
            if (segments.length == 2 && StringUtils.hasText(segments[1])) {
                return segments[1];
            }
        }

        return null;
    }

    private String extractText(JsonNode node, String... candidateKeys) {
        if (node == null) {
            return null;
        }

        for (String key : candidateKeys) {
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

    private String normalizeEventType(String rawType) {
        if (!StringUtils.hasText(rawType)) {
            return "";
        }

        String type = rawType;
        int lastDotIndex = type.lastIndexOf('.');
        if (lastDotIndex >= 0 && lastDotIndex < type.length() - 1) {
            type = type.substring(lastDotIndex + 1);
        }

        if (type.endsWith("Event")) {
            type = type.substring(0, type.length() - "Event".length());
        }

        return type;
    }
}
