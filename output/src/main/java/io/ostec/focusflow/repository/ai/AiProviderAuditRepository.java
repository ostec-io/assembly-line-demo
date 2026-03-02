package io.ostec.focusflow.repository.ai;

import io.ostec.focusflow.document.ai.AiProviderAuditDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface AiProviderAuditRepository extends MongoRepository<AiProviderAuditDocument, String> {

    Optional<AiProviderAuditDocument> findByIdAndTenantIdAndOrgId(String id, String tenantId, String orgId);
}
