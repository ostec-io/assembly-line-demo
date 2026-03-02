package io.ostec.focusflow.repository.entitlement;

import io.ostec.focusflow.document.entitlement.SubscriptionAccountDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface SubscriptionAccountRepository extends MongoRepository<SubscriptionAccountDocument, String> {

    Optional<SubscriptionAccountDocument> findByTenantIdAndOrgIdAndUserId(
        String tenantId,
        String orgId,
        String userId
    );
}
