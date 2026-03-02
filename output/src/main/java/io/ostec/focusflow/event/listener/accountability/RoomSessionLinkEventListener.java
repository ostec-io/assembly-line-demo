package io.ostec.focusflow.event.listener.accountability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ostec.focusflow.event.model.DomainEventEnvelope;
import io.ostec.focusflow.service.accountability.AccountabilityRoomCommandService;
import io.ostec.foundation.security.tenant.TenantContext;
import io.ostec.foundation.security.tenant.TenantContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class RoomSessionLinkEventListener {

    private static final Logger log = LoggerFactory.getLogger(RoomSessionLinkEventListener.class);

    private final AccountabilityRoomCommandService accountabilityRoomCommandService;
    private final ObjectMapper objectMapper;

    public RoomSessionLinkEventListener(
        AccountabilityRoomCommandService accountabilityRoomCommandService,
        ObjectMapper objectMapper
    ) {
        this.accountabilityRoomCommandService = accountabilityRoomCommandService;
        this.objectMapper = objectMapper;
    }

    @EventListener
    public void onDomainEvent(DomainEventEnvelope envelope) {
        String normalizedType = normalizeEventType(envelope.eventType());
        if (!"FocusSessionStarted".equals(normalizedType) && !"FocusSessionCompleted".equals(normalizedType)) {
            return;
        }

        JsonNode payload = parsePayload(envelope.payload());
        String roomId = extractText(payload, "roomId", "accountabilityRoomId");
        if (!StringUtils.hasText(roomId)) {
            return;
        }

        String userId = resolveUserId(payload, envelope);
        withTenantContext(envelope, userId, () -> handleSessionEvent(normalizedType, roomId, payload));
    }

    private void handleSessionEvent(String normalizedType, String roomId, JsonNode payload) {
        try {
            switch (normalizedType) {
                case "FocusSessionStarted" -> accountabilityRoomCommandService.startRoomSession(roomId);
                case "FocusSessionCompleted" -> accountabilityRoomCommandService.submitParticipantResult(
                    roomId,
                    resolveCompletionSummary(payload),
                    extractInteger(payload, "deepWorkMinutes", "focusedMinutes")
                );
                default -> {
                    // No-op: guarded by caller.
                }
            }
        } catch (IllegalStateException ex) {
            log.debug(
                "Ignoring {} room linkage for roomId={} due to state mismatch: {}",
                normalizedType,
                roomId,
                ex.getMessage()
            );
        }
    }

    private String resolveCompletionSummary(JsonNode payload) {
        String summary = extractText(payload, "summary", "completionSummary", "resultSummary");
        return StringUtils.hasText(summary) ? summary : "Focus session completed";
    }

    private void withTenantContext(DomainEventEnvelope envelope, String userId, Runnable action) {
        if (!StringUtils.hasText(envelope.tenantId()) || !StringUtils.hasText(envelope.orgId())) {
            log.warn("Skipping event {} because tenant/org context is missing", envelope.eventType());
            return;
        }

        String effectiveUserId = StringUtils.hasText(userId) ? userId : resolveUserFromProducer(envelope.producer());
        if (!StringUtils.hasText(effectiveUserId)) {
            effectiveUserId = "system";
        }

        TenantContext previous = TenantContextHolder.getContext();
        boolean hadPreviousContext = TenantContextHolder.hasContext();

        try {
            TenantContextHolder.setContext(TenantContext.builder()
                .tenantId(envelope.tenantId())
                .orgId(envelope.orgId())
                .userId(effectiveUserId)
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
            log.warn("Unable to parse focus session payload for room linkage", ex);
            return objectMapper.createObjectNode();
        }
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
