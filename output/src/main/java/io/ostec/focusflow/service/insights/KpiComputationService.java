package io.ostec.focusflow.service.insights;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ostec.focusflow.document.insights.AnalyticsFactDocument;
import io.ostec.focusflow.document.insights.KpiSnapshotDocument;
import io.ostec.focusflow.event.model.insights.KpiSnapshotPublishedEvent;
import io.ostec.focusflow.event.publisher.OutboxPublisherService;
import io.ostec.focusflow.repository.insights.KpiSnapshotRepository;
import io.ostec.foundation.security.tenant.TenantContext;
import io.ostec.foundation.security.tenant.TenantContextHolder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class KpiComputationService {

    private static final String AGGREGATE_TYPE = "KpiSnapshot";
    private static final String KPI_SNAPSHOT_PUBLISHED = "KpiSnapshotPublished";
    private static final String EVENT_VERSION = "1";

    private final MongoTemplate mongoTemplate;
    private final KpiSnapshotRepository kpiSnapshotRepository;
    private final OutboxPublisherService outboxPublisherService;
    private final ObjectMapper objectMapper;
    private final long snapshotWindowMinutes;

    public KpiComputationService(
        MongoTemplate mongoTemplate,
        KpiSnapshotRepository kpiSnapshotRepository,
        OutboxPublisherService outboxPublisherService,
        ObjectMapper objectMapper,
        @Value("${focusflow.insights.kpi.window-minutes:60}") long snapshotWindowMinutes
    ) {
        this.mongoTemplate = mongoTemplate;
        this.kpiSnapshotRepository = kpiSnapshotRepository;
        this.outboxPublisherService = outboxPublisherService;
        this.objectMapper = objectMapper;
        this.snapshotWindowMinutes = Math.max(1, snapshotWindowMinutes);
    }

    @Transactional
    @Scheduled(fixedDelayString = "${focusflow.insights.kpi.interval-ms:300000}")
    public void computeScheduledSnapshots() {
        Instant windowEnd = Instant.now().truncatedTo(ChronoUnit.MINUTES);
        Instant windowStart = windowEnd.minus(Duration.ofMinutes(snapshotWindowMinutes));
        computeSnapshotsForWindow(windowStart, windowEnd);
    }

    @Transactional
    public List<KpiSnapshotDocument> computeSnapshotsForWindow(Instant windowStart, Instant windowEnd) {
        Instant effectiveWindowEnd = windowEnd != null ? windowEnd : Instant.now().truncatedTo(ChronoUnit.MINUTES);
        Instant effectiveWindowStart = windowStart != null
            ? windowStart
            : effectiveWindowEnd.minus(Duration.ofMinutes(snapshotWindowMinutes));

        if (!effectiveWindowStart.isBefore(effectiveWindowEnd)) {
            return List.of();
        }

        List<AnalyticsFactDocument> windowFacts = loadFactsForWindow(effectiveWindowStart, effectiveWindowEnd);
        if (windowFacts.isEmpty()) {
            return List.of();
        }

        Map<ScopeKey, List<AnalyticsFactDocument>> factsByScope = windowFacts.stream()
            .filter(this::hasScope)
            .collect(Collectors.groupingBy(
                fact -> new ScopeKey(fact.getTenantId(), fact.getOrgId()),
                LinkedHashMap::new,
                Collectors.toList()
            ));

        List<KpiSnapshotDocument> persistedSnapshots = new ArrayList<>();
        for (Map.Entry<ScopeKey, List<AnalyticsFactDocument>> scopeEntry : factsByScope.entrySet()) {
            ScopeKey scope = scopeEntry.getKey();
            List<AnalyticsFactDocument> scopedFacts = scopeEntry.getValue();
            persistedSnapshots.addAll(computeScopeSnapshots(scope, scopedFacts, effectiveWindowStart, effectiveWindowEnd));
        }

        return persistedSnapshots;
    }

    private List<AnalyticsFactDocument> loadFactsForWindow(Instant windowStart, Instant windowEnd) {
        Query query = Query.query(Criteria.where("occurredAt").gte(windowStart).lt(windowEnd));
        return mongoTemplate.find(query, AnalyticsFactDocument.class);
    }

    private boolean hasScope(AnalyticsFactDocument fact) {
        return StringUtils.hasText(fact.getTenantId()) && StringUtils.hasText(fact.getOrgId());
    }

    private List<KpiSnapshotDocument> computeScopeSnapshots(
        ScopeKey scope,
        List<AnalyticsFactDocument> scopedFacts,
        Instant windowStart,
        Instant windowEnd
    ) {
        long ingestedEventCount = scopedFacts.size();

        Set<String> activeUsers = scopedFacts.stream()
            .map(AnalyticsFactDocument::getUserId)
            .filter(StringUtils::hasText)
            .collect(Collectors.toCollection(LinkedHashSet::new));

        long sessionStartedCount = scopedFacts.stream()
            .map(AnalyticsFactDocument::getEventType)
            .map(this::normalizeEventType)
            .filter("FocusSessionStarted"::equals)
            .count();

        long sessionCompletedCount = scopedFacts.stream()
            .map(AnalyticsFactDocument::getEventType)
            .map(this::normalizeEventType)
            .filter("FocusSessionCompleted"::equals)
            .count();

        long sessionInterruptedCount = scopedFacts.stream()
            .map(AnalyticsFactDocument::getEventType)
            .map(this::normalizeEventType)
            .filter("FocusSessionInterrupted"::equals)
            .count();

        double completionRate = sessionStartedCount > 0
            ? (double) sessionCompletedCount / (double) sessionStartedCount
            : 0.0;
        double dropOffRate = sessionStartedCount > 0
            ? (double) sessionInterruptedCount / (double) sessionStartedCount
            : 0.0;

        List<KpiSnapshotDocument> snapshots = new ArrayList<>();
        snapshots.add(upsertAndPublishSnapshot(
            scope,
            "events.ingested.count",
            (double) ingestedEventCount,
            windowStart,
            windowEnd,
            Map.of("windowMinutes", String.valueOf(snapshotWindowMinutes), "unit", "count")
        ));
        snapshots.add(upsertAndPublishSnapshot(
            scope,
            "users.active.count",
            (double) activeUsers.size(),
            windowStart,
            windowEnd,
            Map.of("windowMinutes", String.valueOf(snapshotWindowMinutes), "unit", "count")
        ));
        snapshots.add(upsertAndPublishSnapshot(
            scope,
            "sessions.completion.rate",
            completionRate,
            windowStart,
            windowEnd,
            Map.of(
                "windowMinutes", String.valueOf(snapshotWindowMinutes),
                "unit", "ratio",
                "numerator", "completed",
                "denominator", "started"
            )
        ));
        snapshots.add(upsertAndPublishSnapshot(
            scope,
            "sessions.dropoff.rate",
            dropOffRate,
            windowStart,
            windowEnd,
            Map.of(
                "windowMinutes", String.valueOf(snapshotWindowMinutes),
                "unit", "ratio",
                "numerator", "interrupted",
                "denominator", "started"
            )
        ));

        return snapshots;
    }

    private KpiSnapshotDocument upsertAndPublishSnapshot(
        ScopeKey scope,
        String kpiKey,
        Double value,
        Instant windowStart,
        Instant windowEnd,
        Map<String, String> dimensions
    ) {
        Instant computedAt = Instant.now();
        String snapshotId = deterministicId(
            scope.tenantId(),
            scope.orgId(),
            "snapshot",
            kpiKey + ":" + windowStart.toEpochMilli() + ":" + windowEnd.toEpochMilli() + ":" + dimensionsHash(dimensions)
        );

        KpiSnapshotDocument snapshotDocument = new KpiSnapshotDocument();
        snapshotDocument.setId(snapshotId);
        snapshotDocument.setTenantId(scope.tenantId());
        snapshotDocument.setOrgId(scope.orgId());
        snapshotDocument.setKpiKey(kpiKey);
        snapshotDocument.setWindowStart(windowStart);
        snapshotDocument.setWindowEnd(windowEnd);
        snapshotDocument.setValue(value);
        snapshotDocument.setDimensions(new LinkedHashMap<>(dimensions));
        snapshotDocument.setComputedAt(computedAt);

        KpiSnapshotDocument persistedSnapshot = kpiSnapshotRepository.save(snapshotDocument);

        String eventId = deterministicId(scope.tenantId(), scope.orgId(), KPI_SNAPSHOT_PUBLISHED, snapshotId);
        String correlationId = "kpi:" + scope.tenantId() + ":" + scope.orgId() + ":" + windowEnd.toEpochMilli();
        KpiSnapshotPublishedEvent publishedEvent = new KpiSnapshotPublishedEvent(
            eventId,
            computedAt,
            scope.tenantId(),
            scope.orgId(),
            snapshotId,
            kpiKey,
            windowStart,
            windowEnd,
            value,
            new LinkedHashMap<>(dimensions)
        );

        withTenantContext(scope.tenantId(), scope.orgId(), "system", correlationId, () -> outboxPublisherService.enqueueWithEventId(
            publishedEvent.eventId(),
            AGGREGATE_TYPE,
            persistedSnapshot.getId(),
            KPI_SNAPSHOT_PUBLISHED,
            EVENT_VERSION,
            toJson(publishedEvent),
            correlationId,
            persistedSnapshot.getId()
        ));

        return persistedSnapshot;
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
                .userId(userId)
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

    private String dimensionsHash(Map<String, String> dimensions) {
        if (dimensions == null || dimensions.isEmpty()) {
            return "none";
        }

        String material = dimensions.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(entry -> entry.getKey() + "=" + entry.getValue())
            .collect(Collectors.joining("|"));

        return UUID.nameUUIDFromBytes(material.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private String deterministicId(String tenantId, String orgId, String type, String materialSuffix) {
        String material = tenantId + ":" + orgId + ":" + type + ":" + materialSuffix;
        return UUID.nameUUIDFromBytes(material.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private String toJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to serialize KPI snapshot event", ex);
        }
    }

    private record ScopeKey(String tenantId, String orgId) {
    }
}
