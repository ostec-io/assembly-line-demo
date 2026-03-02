package io.ostec.focusflow.repository.ai;

import io.ostec.focusflow.document.ai.SessionPlanArtifactDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface SessionPlanRepository extends MongoRepository<SessionPlanArtifactDocument, String> {

    Optional<SessionPlanArtifactDocument> findByIdAndTenantIdAndOrgId(String id, String tenantId, String orgId);

    Optional<SessionPlanArtifactDocument> findFirstByTenantIdAndOrgIdAndSessionIdOrderByRevisionDesc(
        String tenantId,
        String orgId,
        String sessionId
    );
}
