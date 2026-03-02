package io.ostec.focusflow.repository.insights;

import io.ostec.focusflow.document.insights.KpiSnapshotDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface KpiSnapshotRepository extends MongoRepository<KpiSnapshotDocument, String> {

    List<KpiSnapshotDocument> findTop20ByTenantIdAndOrgIdAndKpiKeyOrderByComputedAtDesc(
        String tenantId,
        String orgId,
        String kpiKey
    );
}
