package io.ostec.focusflow.repository.insights;

import io.ostec.focusflow.document.insights.AnalyticsFactDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.Instant;
import java.util.List;

public interface AnalyticsFactRepository extends MongoRepository<AnalyticsFactDocument, String> {

    boolean existsByTenantIdAndOrgIdAndEventId(String tenantId, String orgId, String eventId);

    List<AnalyticsFactDocument> findByTenantIdAndOrgIdAndEventTypeAndOccurredAtBetween(
        String tenantId,
        String orgId,
        String eventType,
        Instant fromOccurredAt,
        Instant toOccurredAt
    );
}
