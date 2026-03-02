package io.ostec.focusflow.event.listener.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ostec.focusflow.event.model.DomainEventEnvelope;
import io.ostec.focusflow.service.session.SessionCommandService;
import io.ostec.foundation.security.tenant.TenantContext;
import io.ostec.foundation.security.tenant.TenantContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class SessionPlanEventListener {

    private static final Logger log = LoggerFactory.getLogger(SessionPlanEventListener.class);

    private final SessionCommandService sessionCommandService;
    private final ObjectMapper objectMapper;

    public SessionPlanEventListener(SessionCommandService sessionCommandService, ObjectMapper objectMapper) {
        this.sessionCommandService = sessionCommandService;
        this.objectMapper = objectMapper;
    }

    @EventListener
    public void onDomainEvent(DomainEventEnvelope envelope) {
        if (!"SessionPlanGenerated".equals(normalizeEventType(envelope.eventType()))) {
            return;
        }

        JsonNode payload = parsePayload(envelope.payload());
        String userId = extractText(payload, "userId", "subjectUserId", "ownerUserId", "actorUserId");
        String sessionId = extractText(payload, "sessionId");
        String planId = extractText(payload, "planId");
        Integer taskCount = extractInteger(payload, "taskCount", "task_count", "itemsCount");

        if (!StringUtils.hasText(sessionId) || !StringUtils.hasText(planId)) {
            log.warn("Skipping SessionPlanGenerated handling because sessionId or planId is missing");
            return;
        }

        withTenantContext(envelope, userId, () -> sessionCommandService.attachSessionPlan(sessionId, planId, taskCount));
    }

    private void withTenantContext(DomainEventEnvelope envelope, String userId, Runnable action) {
        if (!StringUtils.hasText(envelope.tenantId()) || !StringUtils.hasText(envelope.orgId())) {
            log.warn("Skipping event {} because tenant/org context is missing", envelope.eventType());
            return;
        }

        String effectiveUserId = StringUtils.hasText(userId) ? userId : extractUserFromProducer(envelope.producer());
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
            log.warn("Unable to parse SessionPlanGenerated payload", ex);
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

    private String extractUserFromProducer(String producer) {
        if (!StringUtils.hasText(producer) || !producer.contains(":")) {
            return null;
        }

        String[] parts = producer.split(":", 2);
        if (parts.length < 2 || !StringUtils.hasText(parts[1])) {
            return null;
        }

        return parts[1];
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
