package io.ostec.focusflow.event.listener.accountability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ostec.focusflow.event.model.DomainEventEnvelope;
import io.ostec.focusflow.service.accountability.RoomModerationService;
import io.ostec.foundation.security.tenant.TenantContext;
import io.ostec.foundation.security.tenant.TenantContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class RoomEntitlementEventListener {

    private static final Logger log = LoggerFactory.getLogger(RoomEntitlementEventListener.class);

    private final RoomModerationService roomModerationService;
    private final ObjectMapper objectMapper;

    public RoomEntitlementEventListener(
        RoomModerationService roomModerationService,
        ObjectMapper objectMapper
    ) {
        this.roomModerationService = roomModerationService;
        this.objectMapper = objectMapper;
    }

    @EventListener
    public void onDomainEvent(DomainEventEnvelope envelope) {
        String normalizedType = normalizeEventType(envelope.eventType());
        if (!"EntitlementGranted".equals(normalizedType) && !"EntitlementRevoked".equals(normalizedType)) {
            return;
        }

        JsonNode payload = parsePayload(envelope.payload());
        String userId = resolveUserId(payload, envelope);
        if (!StringUtils.hasText(userId)) {
            log.warn("Skipping {} because userId could not be resolved", normalizedType);
            return;
        }

        withTenantContext(envelope, userId, () -> {
            if ("EntitlementGranted".equals(normalizedType)) {
                String reason = extractText(payload, "reason", "message");
                roomModerationService.upsertEntitlementState(userId, true, reason, envelope.eventId());
            } else {
                String reason = extractText(payload, "reason", "revocationReason", "message");
                roomModerationService.upsertEntitlementState(userId, false, reason, envelope.eventId());
            }
        });
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
            log.warn("Unable to parse entitlement payload for room listener", ex);
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

        if (StringUtils.hasText(envelope.producer()) && envelope.producer().contains(":")) {
            String[] parts = envelope.producer().split(":", 2);
            if (parts.length == 2 && StringUtils.hasText(parts[1])) {
                return parts[1];
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
