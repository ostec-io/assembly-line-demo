package io.ostec.focusflow.repository.entitlement;

import io.ostec.focusflow.document.entitlement.BillingWebhookInboxDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface BillingWebhookInboxRepository extends MongoRepository<BillingWebhookInboxDocument, String> {

    boolean existsByTenantIdAndOrgIdAndProviderAndEventId(
        String tenantId,
        String orgId,
        String provider,
        String eventId
    );
}
