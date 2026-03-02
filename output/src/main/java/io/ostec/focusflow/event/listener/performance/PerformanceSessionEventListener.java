package io.ostec.focusflow.event.listener.performance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ostec.focusflow.event.model.DomainEventEnvelope;
import io.ostec.focusflow.service.performance.FocusScoreCalculationService;
import io.ostec.foundation.security.tenant.TenantContext;
import io.ostec.foundation.security.tenant.TenantContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Set;

@Component
public class PerformanceSessionEventListener {

    private static final Logger log = LoggerFactory.getLogger(PerformanceSessionEventListener.class);

    private static final String SESSION_TOPIC = "focus.sessions.v1";
    private static final String SESSION_AGGREGATE_TYPE = "FocusSession";
    private static final Set<String> SUPPORTED_EVENT_TYPES = Set.of(
        "FocusSessionStarted",
        "FocusSessionCompleted",
        "FocusSessionInterrupted"
    );

    private final FocusScoreCalculationService focusScoreCalculationService;
    private final ObjectMapper objectMapper;

    public PerformanceSessionEventListener(
        FocusScoreCalculationService focusScoreCalculationService,
        ObjectMapper objectMapper
    ) {
        this.focusScoreCalculationService = focusScoreCalculationService;
        this.objectMapper = objectMapper;
    }

    @EventListener
    public void onDomainEvent(DomainEventEnvelope envelope) {
        if (!belongsToTopic(envelope, SESSION_TOPIC, SESSION_AGGREGATE_TYPE)) {
            return;
        }

        String normalizedType = normalizeEventType(envelope.eventType());
        if (!SUPPORTED_EVENT_TYPES.contains(normalizedType)) {
            return;
        }

        JsonNode payload = parsePayload(envelope.payload());
        String userId = resolveUserId(payload, envelope);
        if (!StringUtils.hasText(userId)) {
            log.warn("Skipping {} because userId could not be resolved", normalizedType);
            return;
        }

        Instant occurredAt = resolveOccurredAt(payload, envelope, normalizedType);
        Integer deepWorkMinutes = extractInteger(payload, "deepWorkMinutes", "focusedMinutes", "minutes");
        String summary = extractText(payload, "summary", "completionSummary", "resultSummary");
        ZoneId zoneId = resolveZoneId(payload);

        withTenantContext(envelope, userId, () -> focusScoreCalculationService.calculateDailyScore(
            envelope.eventId(),
            normalizedType,
            occurredAt,
            deepWorkMinutes,
            summary,
            zoneId,
            envelope.correlationId(),
            envelope.eventId()
        ));
    }

    private boolean belongsToTopic(DomainEventEnvelope envelope, String expectedTopic, String expectedAggregateType) {
        if (StringUtils.hasText(envelope.eventType()) && envelope.eventType().startsWith(expectedTopic + ".")) {
            return true;
        }

        return StringUtils.hasText(envelope.aggregateType())
            && expectedAggregateType.equalsIgnoreCase(envelope.aggregateType());
    }

    private Instant resolveOccurredAt(JsonNode payload, DomainEventEnvelope envelope, String normalizedType) {
        if ("FocusSessionCompleted".equals(normalizedType)) {
            Instant completedAt = extractInstant(payload, "endedAt", "completedAt", "occurredAt");
            if (completedAt != null) {
                return completedAt;
            }
        }

        if ("FocusSessionInterrupted".equals(normalizedType)) {
            Instant interruptedAt = extractInstant(payload, "detectedAt", "occurredAt");
            if (interruptedAt != null) {
                return interruptedAt;
            }
        }

        Instant startedAt = extractInstant(payload, "startedAt", "occurredAt");
        if (startedAt != null) {
            return startedAt;
        }

        return envelope.occurredAt() != null ? envelope.occurredAt() : Instant.now();
    }

    private ZoneId resolveZoneId(JsonNode payload) {
        String zoneText = extractText(payload, "timezone", "timeZone", "zoneId");
        if (!StringUtils.hasText(zoneText)) {
            return ZoneOffset.UTC;
        }

        try {
            return ZoneId.of(zoneText.trim());
        } catch (Exception ex) {
            log.debug("Ignoring invalid timezone '{}' in session payload", zoneText);
            return ZoneOffset.UTC;
        }
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
            log.warn("Unable to parse performance session payload", ex);
            return objectMapper.createObjectNode();
        }
    }

    private Instant extractInstant(JsonNode node, String... keys) {
        String value = extractText(node, keys);
        if (!StringUtils.hasText(value)) {
            return null;
        }

        try {
            return Instant.parse(value);
        } catch (Exception ex) {
            return null;
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

        if (StringUtils.hasText(envelope.producer()) && envelope.producer().contains(":")) {
            String[] parts = envelope.producer().split(":", 2);
            if (parts.length == 2 && StringUtils.hasText(parts[1])) {
                return parts[1];
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
