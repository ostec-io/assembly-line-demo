package io.ostec.focusflow.repository.ai;

import io.ostec.focusflow.document.ai.PlanMemoryDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface PlanMemoryRepository extends MongoRepository<PlanMemoryDocument, String> {

    Optional<PlanMemoryDocument> findByTenantIdAndOrgIdAndUserId(String tenantId, String orgId, String userId);
}
