package io.ostec.focusflow.event.listener.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ostec.focusflow.event.model.DomainEventEnvelope;
import io.ostec.focusflow.service.session.SessionPolicyService;
import io.ostec.foundation.security.tenant.TenantContext;
import io.ostec.foundation.security.tenant.TenantContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Set;

@Component
public class AiEntitlementEventListener {

    private static final Logger log = LoggerFactory.getLogger(AiEntitlementEventListener.class);

    private static final String PLAN_MEMORY_POLICY = "plan-memory";
    private static final String ALLOW = "ALLOW";
    private static final String DENY = "DENY";
    private static final Set<String> SUPPORTED_EVENTS = Set.of(
        "SubscriptionActivated",
        "EntitlementGranted",
        "EntitlementRevoked"
    );

    private final SessionPolicyService sessionPolicyService;
    private final ObjectMapper objectMapper;

    public AiEntitlementEventListener(
        SessionPolicyService sessionPolicyService,
        ObjectMapper objectMapper
    ) {
        this.sessionPolicyService = sessionPolicyService;
        this.objectMapper = objectMapper;
    }

    @EventListener
    public void onDomainEvent(DomainEventEnvelope envelope) {
        String normalizedType = normalizeEventType(envelope.eventType());
        if (!SUPPORTED_EVENTS.contains(normalizedType)) {
            return;
        }

        JsonNode payload = parsePayload(envelope.payload());
        if (!isPlanMemoryCapabilityEvent(normalizedType, payload)) {
            return;
        }

        String userId = resolveUserId(payload, envelope);
        if (!StringUtils.hasText(userId)) {
            log.warn("Skipping {} because userId could not be resolved", normalizedType);
            return;
        }

        withTenantContext(envelope, userId, () -> handleEntitlementEvent(normalizedType, payload, envelope.eventId(), userId));
    }

    private void handleEntitlementEvent(String normalizedType, JsonNode payload, String sourceEventId, String userId) {
        String reason = extractText(payload, "reason", "message", "description");
        String detail = "sourceEventId=" + sourceEventId;

        if ("EntitlementRevoked".equals(normalizedType)) {
            sessionPolicyService.upsertUserPolicyState(
                PLAN_MEMORY_POLICY,
                userId,
                DENY,
                StringUtils.hasText(reason) ? reason : "Plan memory entitlement revoked",
                detail
            );
            return;
        }

        String grantedReason = StringUtils.hasText(reason)
            ? reason
            : ("SubscriptionActivated".equals(normalizedType)
                ? "Subscription activated for plan memory capability"
                : "Plan memory entitlement granted");

        sessionPolicyService.upsertUserPolicyState(
            PLAN_MEMORY_POLICY,
            userId,
            ALLOW,
            grantedReason,
            detail
        );
    }

    private boolean isPlanMemoryCapabilityEvent(String eventType, JsonNode payload) {
        if ("SubscriptionActivated".equals(eventType)) {
            return true;
        }

        String directCapability = extractText(
            payload,
            "capability",
            "feature",
            "entitlement",
            "capabilityCode",
            "featureCode",
            "entitlementCode",
            "permission"
        );
        if (StringUtils.hasText(directCapability)) {
            return matchesPlanMemoryCapability(directCapability);
        }

        Boolean collectionMatch = matchCapabilityFromCollections(payload);
        if (collectionMatch != null) {
            return collectionMatch;
        }

        // When capability metadata is omitted, treat explicit entitlement events as plan-memory signals.
        return "EntitlementGranted".equals(eventType) || "EntitlementRevoked".equals(eventType);
    }

    private Boolean matchCapabilityFromCollections(JsonNode payload) {
        if (payload == null) {
            return null;
        }

        for (String key : ListKeys.CAPABILITY_ARRAY_KEYS) {
            JsonNode collection = payload.get(key);
            if (collection == null || !collection.isArray()) {
                continue;
            }

            for (JsonNode node : collection) {
                if (node == null) {
                    continue;
                }

                if (node.isTextual() && matchesPlanMemoryCapability(node.asText())) {
                    return Boolean.TRUE;
                }

                if (node.isObject()) {
                    String name = extractText(node, "name", "code", "id", "value", "capability", "entitlement");
                    if (StringUtils.hasText(name) && matchesPlanMemoryCapability(name)) {
                        return Boolean.TRUE;
                    }
                }
            }

            // Metadata exists but did not include the capability.
            return Boolean.FALSE;
        }

        return null;
    }

    private boolean matchesPlanMemoryCapability(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        return normalized.contains("plan-memory")
            || normalized.contains("plan_memory")
            || normalized.contains("planmemory")
            || normalized.contains("ai-plan-memory")
            || normalized.contains("ai.plan.memory");
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
            log.warn("Unable to parse AI entitlement payload", ex);
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

    private static final class ListKeys {
        private static final String[] CAPABILITY_ARRAY_KEYS = {
            "capabilities",
            "features",
            "entitlements",
            "permissions"
        };

        private ListKeys() {
        }
    }
}
