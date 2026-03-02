package io.ostec.focusflow.repository.entitlement;

import io.ostec.focusflow.document.entitlement.UsageCounterDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface UsageCounterRepository extends MongoRepository<UsageCounterDocument, String> {

    Optional<UsageCounterDocument> findByTenantIdAndOrgIdAndUserIdAndFeatureKeyAndUsageDate(
        String tenantId,
        String orgId,
        String userId,
        String featureKey,
        LocalDate usageDate
    );
}
