package io.ostec.focusflow.service.insights;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ostec.focusflow.document.insights.AnalyticsFactDocument;
import io.ostec.focusflow.document.insights.ProjectionLagDocument;
import io.ostec.focusflow.event.model.DomainEventEnvelope;
import io.ostec.focusflow.event.model.insights.AnalyticsEventIngestedEvent;
import io.ostec.focusflow.event.publisher.OutboxPublisherService;
import io.ostec.focusflow.repository.insights.AnalyticsFactRepository;
import io.ostec.focusflow.repository.insights.ProjectionLagRepository;
import io.ostec.foundation.security.tenant.TenantContext;
import io.ostec.foundation.security.tenant.TenantContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class AnalyticsIngestionService {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsIngestionService.class);

    private static final String ANALYTICS_FACT_AGGREGATE_TYPE = "AnalyticsFact";
    private static final String ANALYTICS_EVENT_INGESTED = "AnalyticsEventIngested";
    private static final String EVENT_VERSION = "1";

    private final AnalyticsFactRepository analyticsFactRepository;
    private final ProjectionLagRepository projectionLagRepository;
    private final OutboxPublisherService outboxPublisherService;
    private final ObjectMapper objectMapper;

    public AnalyticsIngestionService(
        AnalyticsFactRepository analyticsFactRepository,
        ProjectionLagRepository projectionLagRepository,
        OutboxPublisherService outboxPublisherService,
        ObjectMapper objectMapper
    ) {
        this.analyticsFactRepository = analyticsFactRepository;
        this.projectionLagRepository = projectionLagRepository;
        this.outboxPublisherService = outboxPublisherService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public IngestionResult ingest(DomainEventEnvelope envelope) {
        if (envelope == null) {
            return new IngestionResult(IngestionStatus.SKIPPED_INVALID, null, null);
        }

        if (isInsightsSelfEvent(envelope)) {
            return new IngestionResult(IngestionStatus.SKIPPED_NON_DOMAIN, null, null);
        }

        if (!isValidEnvelope(envelope)) {
            log.warn("Skipping analytics ingestion due to invalid envelope metadata: eventType={}", envelope.eventType());
            return new IngestionResult(IngestionStatus.SKIPPED_INVALID, null, null);
        }

        if (analyticsFactRepository.existsByTenantIdAndOrgIdAndEventId(
            envelope.tenantId(),
            envelope.orgId(),
            envelope.eventId()
        )) {
            return new IngestionResult(IngestionStatus.DUPLICATE, null, envelope.eventId());
        }

        Map<String, Object> payload = toPayloadMap(envelope.payload());
        String sourceContext = resolveSourceContext(envelope.eventType(), envelope.aggregateType());
        Instant occurredAt = envelope.occurredAt() != null ? envelope.occurredAt() : Instant.now();
        String userId = resolveUserId(payload, envelope.producer());

        AnalyticsFactDocument factDocument = new AnalyticsFactDocument();
        factDocument.setTenantId(envelope.tenantId());
        factDocument.setOrgId(envelope.orgId());
        factDocument.setEventId(envelope.eventId());
        factDocument.setEventType(envelope.eventType());
        factDocument.setSourceContext(sourceContext);
        factDocument.setOccurredAt(occurredAt);
        factDocument.setUserId(userId);
        factDocument.setPayload(payload);

        AnalyticsFactDocument persisted;
        try {
            persisted = analyticsFactRepository.save(factDocument);
        } catch (DuplicateKeyException ex) {
            return new IngestionResult(IngestionStatus.DUPLICATE, null, envelope.eventId());
        }

        updateProjectionLag(envelope.tenantId(), envelope.orgId(), resolveStream(envelope.eventType(), sourceContext), occurredAt);

        String correlationId = StringUtils.hasText(envelope.correlationId())
            ? envelope.correlationId().trim()
            : envelope.eventId();
        String emittedEventId = deterministicEventId(
            envelope.tenantId(),
            envelope.orgId(),
            ANALYTICS_EVENT_INGESTED,
            envelope.eventId()
        );

        AnalyticsEventIngestedEvent ingestedEvent = new AnalyticsEventIngestedEvent(
            emittedEventId,
            Instant.now(),
            envelope.tenantId(),
            envelope.orgId(),
            envelope.eventId(),
            envelope.eventType(),
            sourceContext,
            userId,
            persisted.getId()
        );

        publishIngestionEvent(
            ingestedEvent,
            persisted.getId(),
            correlationId,
            envelope.eventId(),
            envelope.tenantId(),
            envelope.orgId(),
            userId
        );

        return new IngestionResult(IngestionStatus.INGESTED, persisted.getId(), envelope.eventId());
    }

    private boolean isValidEnvelope(DomainEventEnvelope envelope) {
        return StringUtils.hasText(envelope.eventId())
            && StringUtils.hasText(envelope.eventType())
            && StringUtils.hasText(envelope.tenantId())
            && StringUtils.hasText(envelope.orgId())
            && StringUtils.hasText(envelope.correlationId());
    }

    private boolean isInsightsSelfEvent(DomainEventEnvelope envelope) {
        String normalizedType = normalizeEventType(envelope.eventType());
        if (ANALYTICS_EVENT_INGESTED.equals(normalizedType) || "KpiSnapshotPublished".equals(normalizedType)) {
            return true;
        }

        String eventType = normalizeLower(envelope.eventType());
        return eventType != null && eventType.startsWith("focus.insights.v1");
    }

    private void updateProjectionLag(String tenantId, String orgId, String stream, Instant occurredAt) {
        if (!StringUtils.hasText(stream)) {
            return;
        }

        ProjectionLagDocument lagDocument = projectionLagRepository
            .findByTenantIdAndOrgIdAndStream(tenantId, orgId, stream)
            .orElseGet(() -> {
                ProjectionLagDocument document = new ProjectionLagDocument();
                document.setTenantId(tenantId);
                document.setOrgId(orgId);
                document.setStream(stream);
                return document;
            });

        Instant lastEventAt = lagDocument.getLastEventAt();
        if (lastEventAt == null || occurredAt.isAfter(lastEventAt)) {
            lagDocument.setLastEventAt(occurredAt);
            lagDocument.setLagSeconds(Math.max(0L, Duration.between(occurredAt, Instant.now()).toSeconds()));
            projectionLagRepository.save(lagDocument);
        }
    }

    private void publishIngestionEvent(
        AnalyticsEventIngestedEvent event,
        String aggregateId,
        String correlationId,
        String causationId,
        String tenantId,
        String orgId,
        String userId
    ) {
        withTenantContext(tenantId, orgId, userId, correlationId, () -> outboxPublisherService.enqueueWithEventId(
            event.eventId(),
            ANALYTICS_FACT_AGGREGATE_TYPE,
            aggregateId,
            ANALYTICS_EVENT_INGESTED,
            EVENT_VERSION,
            toJson(event),
            correlationId,
            causationId
        ));
    }

    private void withTenantContext(
        String tenantId,
        String orgId,
        String userId,
        String correlationId,
        Runnable action
    ) {
        TenantContext previous = TenantContextHolder.getContext();
        boolean hadPreviousContext = TenantContextHolder.hasContext();

        try {
            TenantContextHolder.setContext(TenantContext.builder()
                .tenantId(tenantId)
                .orgId(orgId)
                .userId(StringUtils.hasText(userId) ? userId : "system")
                .correlationId(correlationId)
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

    private Map<String, Object> toPayloadMap(String payload) {
        if (!StringUtils.hasText(payload)) {
            return new LinkedHashMap<>();
        }

        try {
            JsonNode node = objectMapper.readTree(payload);
            if (node != null && node.isObject()) {
                return objectMapper.convertValue(node, new TypeReference<LinkedHashMap<String, Object>>() {
                });
            }

            Map<String, Object> wrappedPayload = new LinkedHashMap<>();
            wrappedPayload.put("_payload", payload);
            return wrappedPayload;
        } catch (Exception ex) {
            Map<String, Object> wrappedPayload = new LinkedHashMap<>();
            wrappedPayload.put("_raw", payload);
            return wrappedPayload;
        }
    }

    private String resolveUserId(Map<String, Object> payload, String producer) {
        String userId = firstNonBlank(
            valueAsString(payload.get("userId")),
            valueAsString(payload.get("subjectUserId")),
            valueAsString(payload.get("ownerUserId")),
            valueAsString(payload.get("actorUserId"))
        );
        if (StringUtils.hasText(userId)) {
            return userId;
        }

        if (StringUtils.hasText(producer) && producer.contains(":")) {
            String[] parts = producer.split(":", 2);
            if (parts.length == 2 && StringUtils.hasText(parts[1])) {
                return parts[1].trim();
            }
        }

        return null;
    }

    private String resolveSourceContext(String eventType, String aggregateType) {
        String normalizedEventType = normalizeLower(eventType);
        if (normalizedEventType != null && normalizedEventType.startsWith("focus.")) {
            String[] parts = normalizedEventType.split("\\.");
            if (parts.length >= 2 && StringUtils.hasText(parts[1])) {
                return singularizeContext(parts[1]);
            }
        }

        if (StringUtils.hasText(aggregateType)) {
            return singularizeContext(aggregateType.trim().toLowerCase(Locale.ROOT));
        }

        return "unknown";
    }

    private String resolveStream(String eventType, String sourceContext) {
        String normalizedEventType = normalizeLower(eventType);
        if (normalizedEventType != null && normalizedEventType.startsWith("focus.")) {
            int versionMarkerIndex = normalizedEventType.indexOf(".v1");
            if (versionMarkerIndex > 0) {
                return normalizedEventType.substring(0, versionMarkerIndex + 3);
            }
        }

        return "focus." + sourceContext + ".v1";
    }

    private String normalizeEventType(String rawType) {
        if (!StringUtils.hasText(rawType)) {
            return "";
        }

        String type = rawType.trim();
        int lastDotIndex = type.lastIndexOf('.');
        if (lastDotIndex >= 0 && lastDotIndex < type.length() - 1) {
            type = type.substring(lastDotIndex + 1);
        }

        if (type.endsWith("Event")) {
            type = type.substring(0, type.length() - "Event".length());
        }

        return type;
    }

    private String singularizeContext(String value) {
        if (!StringUtils.hasText(value)) {
            return "unknown";
        }

        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.endsWith("ies") && normalized.length() > 3) {
            return normalized.substring(0, normalized.length() - 3) + "y";
        }

        if (normalized.endsWith("s") && normalized.length() > 1 && !"ai".equals(normalized)) {
            return normalized.substring(0, normalized.length() - 1);
        }

        return normalized;
    }

    private String deterministicEventId(String tenantId, String orgId, String eventType, String sourceEventId) {
        String material = tenantId + ":" + orgId + ":" + eventType + ":" + sourceEventId;
        return UUID.nameUUIDFromBytes(material.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private String valueAsString(Object value) {
        if (value == null) {
            return null;
        }

        if (value instanceof String text) {
            return text;
        }

        return String.valueOf(value);
    }

    private String firstNonBlank(String... candidates) {
        if (candidates == null) {
            return null;
        }

        for (String candidate : candidates) {
            if (StringUtils.hasText(candidate)) {
                return candidate.trim();
            }
        }

        return null;
    }

    private String normalizeLower(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private String toJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to serialize analytics ingestion event", ex);
        }
    }

    public enum IngestionStatus {
        INGESTED,
        DUPLICATE,
        SKIPPED_INVALID,
        SKIPPED_NON_DOMAIN
    }

    public record IngestionResult(IngestionStatus status, String factId, String sourceEventId) {
    }
}
