package io.ostec.focusflow.controller.insights;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ostec.focusflow.dto.request.insights.IngestAnalyticsEventRequest;
import io.ostec.focusflow.event.model.DomainEventEnvelope;
import io.ostec.focusflow.service.insights.AnalyticsIngestionService;
import io.ostec.foundation.security.tenant.TenantContextHolder;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/v1/insights/internal")
@Tag(name = "Insights Ingestion", description = "Internal endpoint for immutable analytics fact ingestion")
public class AnalyticsIngestionController {

    private static final String EVENT_VERSION = "1";
    private static final String AGGREGATE_TYPE = "AnalyticsFact";
    private static final String PRODUCER_PREFIX = "focus.insights.api";

    private final AnalyticsIngestionService analyticsIngestionService;
    private final ObjectMapper objectMapper;

    public AnalyticsIngestionController(
        AnalyticsIngestionService analyticsIngestionService,
        ObjectMapper objectMapper
    ) {
        this.analyticsIngestionService = analyticsIngestionService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/events")
    @PreAuthorize("hasAuthority('SCOPE_insights.write')")
    @Operation(summary = "Ingest an immutable analytics event fact")
    public AnalyticsIngestionService.IngestionResult ingestEvent(@Valid @RequestBody IngestAnalyticsEventRequest request) {
        String tenantId = TenantContextHolder.requireTenantId();
        String orgId = TenantContextHolder.requireOrgId();

        DomainEventEnvelope envelope = new DomainEventEnvelope(
            request.eventId(),
            request.eventType(),
            EVENT_VERSION,
            request.occurredAt(),
            resolveProducer(),
            resolveCorrelationId(request.eventId()),
            request.eventId(),
            tenantId,
            orgId,
            AGGREGATE_TYPE,
            request.eventId(),
            toJson(request.payload())
        );

        return analyticsIngestionService.ingest(envelope);
    }

    private String resolveProducer() {
        String userId = TenantContextHolder.getUserId();
        if (StringUtils.hasText(userId)) {
            return PRODUCER_PREFIX + ":" + userId.trim();
        }
        return PRODUCER_PREFIX + ":system";
    }

    private String resolveCorrelationId(String fallbackEventId) {
        String correlationId = TenantContextHolder.getCorrelationId();
        if (StringUtils.hasText(correlationId)) {
            return correlationId.trim();
        }
        return fallbackEventId;
    }

    private String toJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to serialize analytics payload", ex);
        }
    }
}
