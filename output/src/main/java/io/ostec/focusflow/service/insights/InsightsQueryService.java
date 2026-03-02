package io.ostec.focusflow.service.insights;

import io.ostec.focusflow.document.insights.AnalyticsFactDocument;
import io.ostec.focusflow.document.insights.KpiSnapshotDocument;
import io.ostec.focusflow.document.insights.ProjectionLagDocument;
import io.ostec.focusflow.exception.ApiErrorCode;
import io.ostec.focusflow.repository.insights.KpiSnapshotRepository;
import io.ostec.focusflow.repository.insights.ProjectionLagRepository;
import io.ostec.foundation.security.tenant.TenantContextHolder;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class InsightsQueryService {

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 500;

    private final MongoTemplate mongoTemplate;
    private final KpiSnapshotRepository kpiSnapshotRepository;
    private final ProjectionLagRepository projectionLagRepository;

    public InsightsQueryService(
        MongoTemplate mongoTemplate,
        KpiSnapshotRepository kpiSnapshotRepository,
        ProjectionLagRepository projectionLagRepository
    ) {
        this.mongoTemplate = mongoTemplate;
        this.kpiSnapshotRepository = kpiSnapshotRepository;
        this.projectionLagRepository = projectionLagRepository;
    }

    public List<AnalyticsFactDocument> findFacts(String eventType, Instant fromOccurredAt, Instant toOccurredAt, int limit) {
        TenantScope scope = resolveScope();

        Query query = Query.query(baseScopeCriteria(scope));
        if (StringUtils.hasText(eventType)) {
            query.addCriteria(Criteria.where("eventType").is(eventType.trim()));
        }

        Instant effectiveTo = toOccurredAt != null ? toOccurredAt : Instant.now();
        Instant effectiveFrom = fromOccurredAt != null ? fromOccurredAt : effectiveTo.minus(24, ChronoUnit.HOURS);
        if (effectiveFrom.isBefore(effectiveTo)) {
            query.addCriteria(Criteria.where("occurredAt").gte(effectiveFrom).lte(effectiveTo));
        }

        query.with(Sort.by(Sort.Direction.DESC, "occurredAt"));
        query.limit(sanitizeLimit(limit));
        return mongoTemplate.find(query, AnalyticsFactDocument.class);
    }

    public List<KpiSnapshotDocument> findLatestKpiSnapshots(String kpiKey, int limit) {
        TenantScope scope = resolveScope();
        int effectiveLimit = sanitizeLimit(limit);

        if (StringUtils.hasText(kpiKey) && effectiveLimit <= 20) {
            return kpiSnapshotRepository.findTop20ByTenantIdAndOrgIdAndKpiKeyOrderByComputedAtDesc(
                scope.tenantId(),
                scope.orgId(),
                kpiKey.trim()
            );
        }

        Query query = Query.query(baseScopeCriteria(scope))
            .with(Sort.by(Sort.Direction.DESC, "computedAt"));
        if (StringUtils.hasText(kpiKey)) {
            query.addCriteria(Criteria.where("kpiKey").is(kpiKey.trim()));
        }

        query.limit(effectiveLimit);
        return mongoTemplate.find(query, KpiSnapshotDocument.class);
    }

    public Optional<ProjectionLagDocument> findProjectionLag(String stream) {
        TenantScope scope = resolveScope();
        if (!StringUtils.hasText(stream)) {
            return Optional.empty();
        }

        return projectionLagRepository.findByTenantIdAndOrgIdAndStream(
            scope.tenantId(),
            scope.orgId(),
            stream.trim().toLowerCase()
        );
    }

    public InsightsOverview getOverview(Instant fromOccurredAt, Instant toOccurredAt) {
        TenantScope scope = resolveScope();
        Instant effectiveTo = toOccurredAt != null ? toOccurredAt : Instant.now();
        Instant effectiveFrom = fromOccurredAt != null ? fromOccurredAt : effectiveTo.minus(24, ChronoUnit.HOURS);

        Query factsQuery = Query.query(baseScopeCriteria(scope))
            .addCriteria(Criteria.where("occurredAt").gte(effectiveFrom).lte(effectiveTo))
            .with(Sort.by(Sort.Direction.DESC, "occurredAt"));
        List<AnalyticsFactDocument> facts = mongoTemplate.find(factsQuery, AnalyticsFactDocument.class);

        Set<String> uniqueEventTypes = new LinkedHashSet<>();
        Set<String> uniqueUsers = new LinkedHashSet<>();
        for (AnalyticsFactDocument fact : facts) {
            if (StringUtils.hasText(fact.getEventType())) {
                uniqueEventTypes.add(fact.getEventType());
            }
            if (StringUtils.hasText(fact.getUserId())) {
                uniqueUsers.add(fact.getUserId());
            }
        }

        Query snapshotsQuery = Query.query(baseScopeCriteria(scope))
            .with(Sort.by(Sort.Direction.DESC, "computedAt"))
            .limit(250);
        List<KpiSnapshotDocument> snapshots = mongoTemplate.find(snapshotsQuery, KpiSnapshotDocument.class);

        Map<String, Double> latestKpis = new LinkedHashMap<>();
        for (KpiSnapshotDocument snapshot : snapshots) {
            if (!StringUtils.hasText(snapshot.getKpiKey())) {
                continue;
            }
            latestKpis.putIfAbsent(snapshot.getKpiKey(), snapshot.getValue());
        }

        Query lagQuery = Query.query(baseScopeCriteria(scope))
            .with(Sort.by(Sort.Direction.DESC, "lagSeconds"));
        List<ProjectionLagDocument> projectionLag = mongoTemplate.find(lagQuery, ProjectionLagDocument.class);

        return new InsightsOverview(
            scope.tenantId(),
            scope.orgId(),
            effectiveFrom,
            effectiveTo,
            facts.size(),
            uniqueEventTypes.size(),
            uniqueUsers.size(),
            latestKpis,
            projectionLag
        );
    }

    private Criteria baseScopeCriteria(TenantScope scope) {
        return Criteria.where("tenantId").is(scope.tenantId()).and("orgId").is(scope.orgId());
    }

    private int sanitizeLimit(int requestedLimit) {
        if (requestedLimit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(requestedLimit, MAX_LIMIT);
    }

    private TenantScope resolveScope() {
        try {
            return new TenantScope(
                TenantContextHolder.requireTenantId(),
                TenantContextHolder.requireOrgId()
            );
        } catch (IllegalStateException ex) {
            throw new IllegalStateException(ApiErrorCode.TENANT_CONTEXT_MISSING.name(), ex);
        }
    }

    public record InsightsOverview(
        String tenantId,
        String orgId,
        Instant fromOccurredAt,
        Instant toOccurredAt,
        int ingestedEventCount,
        int uniqueEventTypeCount,
        int uniqueUserCount,
        Map<String, Double> latestKpis,
        List<ProjectionLagDocument> projectionLag
    ) {
    }

    private record TenantScope(String tenantId, String orgId) {
    }
}
